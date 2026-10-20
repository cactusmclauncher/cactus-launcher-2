package com.cactuslauncher.game;

public final class MemoryAdvisor {
    private MemoryAdvisor() {}

    public static int recommendedMinecraftMemoryGb() {
        return new JavaOptimizer().recommendedHeapGb();
    }
}
