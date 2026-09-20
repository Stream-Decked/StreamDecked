package dev.wolfieboy09.streamdecked.plugin;

public interface StreamDeckedPlugin {
    default void registerLayouts(DeckLayoutRegistry registry) {};
}
