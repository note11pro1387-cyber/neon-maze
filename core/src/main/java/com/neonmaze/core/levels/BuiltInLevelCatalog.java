package com.neonmaze.core.levels;

import com.neonmaze.core.LevelSystem.Catalog;
import com.neonmaze.core.LevelSystem.LevelDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Single registry for APK-bundled, data-only Campaign levels. Adding a level
 * requires one ordered entry here plus localization resources; no screen or
 * Daily Challenge code should carry a parallel level count or ID list.
 */
public final class BuiltInLevelCatalog {
    private BuiltInLevelCatalog() { }

    public static Catalog create() {
        List<LevelDefinition> levels = new ArrayList<>();
        levels.add(Level01.build());
        levels.add(Level02.build());
        levels.add(Level03.build());
        levels.add(Level04.build());
        levels.add(Level05.build());
        if (levels.isEmpty()) throw new IllegalStateException("Built-in Catalog cannot be empty");
        return new Catalog(levels);
    }
}
