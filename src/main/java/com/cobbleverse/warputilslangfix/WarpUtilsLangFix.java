package com.cobbleverse.warputilslangfix;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Fixes WarpUtils showing raw translation keys (feature.warps.*, feature.homes.*).
 *
 * ROOT CAUSE: WarpUtils loads its messages via
 * MessageManager.class.getResourceAsStream("/assets/lang/en_us.json") — a
 * NON-NAMESPACED path. libjf's nested jar (libjf-data-v0) ships its own file at
 * the same path, and whichever jar the classloader consults first wins. On this
 * server libjf wins, so WarpUtils silently parses libjf's 2-key file and every
 * lookup falls back to the raw key.
 *
 * FIX: after server start, read the language + color files directly from the
 * WarpUtils MOD CONTAINER (its own jar — no classloader involved) and overwrite
 * MessageManager's internal maps via reflection. Survives updates of both mods;
 * harmlessly no-ops if WarpUtils ever namespaces its resources upstream.
 */
public final class WarpUtilsLangFix implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("CobbleMasters-WarpUtilsLangFix");
    private static final Gson GSON = new Gson();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> apply());
    }

    private static void apply() {
        try {
            ModContainer warputils = FabricLoader.getInstance().getModContainer("warputils").orElse(null);
            if (warputils == null) {
                LOG.info("WarpUtils not installed — nothing to fix.");
                return;
            }
            Class<?> mm = Class.forName("com.etfl.warputils.common.language.MessageManager");

            // ——— messages (per-language) ———
            Field messagesField = mm.getDeclaredField("messages");
            messagesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Object, Map<String, String>> messages = (Map<Object, Map<String, String>>) messagesField.get(null);

            Class<?> langEnum = Class.forName("com.etfl.warputils.common.language.MessageManager$Language");
            int fixedLangs = 0;
            for (Object lang : langEnum.getEnumConstants()) {
                Path langPath = warputils.findPath("assets/lang/" + lang + ".json").orElse(null);
                if (langPath == null) continue;
                try (Reader reader = Files.newBufferedReader(langPath)) {
                    Map<String, String> parsed = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
                    if (parsed != null && !parsed.isEmpty()) {
                        messages.put(lang, parsed);
                        fixedLangs++;
                        LOG.info("Restored {} '{}' messages from WarpUtils' own jar.", parsed.size(), lang);
                    }
                }
            }

            // ——— colors (same shadowing risk) ———
            Path colorsPath = warputils.findPath("assets/lang/colors.json").orElse(null);
            if (colorsPath != null) {
                Field colorsField = mm.getDeclaredField("colors");
                colorsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, Object> colors = (Map<String, Object>) colorsField.get(null);
                Class<?> formatting = Class.forName("net.minecraft.class_124");
                @SuppressWarnings({"unchecked", "rawtypes"})
                java.util.function.Function<String, Object> valueOf = name -> Enum.valueOf((Class) formatting, name);
                try (Reader reader = Files.newBufferedReader(colorsPath)) {
                    Map<String, String[]> raw = GSON.fromJson(reader, new TypeToken<Map<String, String[]>>() {}.getType());
                    if (raw != null) {
                        for (Map.Entry<String, String[]> e : raw.entrySet()) {
                            Object arr = java.lang.reflect.Array.newInstance(formatting, e.getValue().length);
                            for (int i = 0; i < e.getValue().length; i++) {
                                java.lang.reflect.Array.set(arr, i, valueOf.apply(e.getValue()[i]));
                            }
                            colors.put(e.getKey(), arr);
                        }
                        LOG.info("Restored {} color mappings.", raw.size());
                    }
                }
            }

            if (fixedLangs == 0) {
                LOG.warn("No language files found in WarpUtils jar — mod structure may have changed.");
            }
        } catch (Throwable t) {
            LOG.error("WarpUtils lang fix failed — /warps may show raw keys.", t);
        }
    }
}
