# StreamDecked

A Minecraft mod that gives your mods a Stream Deck.

Ever have a mod with way too many keybinds? Well look no further! You can use Stream Decks to
have less keybinds to press.

StreamDecked connects to the [DeckedOut MC](https://github.com/Stream-Decked/elgato-plugin)
Stream Deck plugin over a local WebSocket, takes the deck over with a profile called Modspace,
and turns that deck into a screen your mods draw their own buttons on.

## Documentation

**[stream-decked.github.io](https://stream-decked.github.io/)**

- [Getting started](https://stream-decked.github.io/streamdecked/users/getting-started.html) for players
- [How it works](https://stream-decked.github.io/streamdecked/how-it-works.html) for the architecture
- [Depending on it](https://stream-decked.github.io/streamdecked/mod-developers/depending.html),
  [plugin interface](https://stream-decked.github.io/streamdecked/mod-developers/plugin.html),
  [events](https://stream-decked.github.io/streamdecked/mod-developers/events.html) and a
  [worked example](https://stream-decked.github.io/streamdecked/mod-developers/example.html)
  for mod developers

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.250 or newer
- Java 21
- The DeckedOut MC Stream Deck plugin, which the mod asks the Stream Deck app to install

## Using it

Drop the mod in your `mods` folder and start the game with a Stream Deck plugged in.

The first time the mod connects, the Stream Deck app asks whether it may install the Modspace
profile. **Accept it.** Nothing is drawn until that profile is on the deck, and if you decline
you will be asked again next time.

Modspace is a folder per mod that registered layouts, and inside each folder are that mod's
buttons. The root page carries a red **Exit** key that hands the deck back to whatever profile
you were using, so you are never stuck. Pressing a Modspace key again re-enters takeover.

On a Stream Deck + or + XL, the dials and touchscreen strip are live too: your mods can react
to a rotation, a push, and taps and holds on the screen. That only works while Modspace is the
active profile, since the plugin only hears from the deck during takeover.

## For mod developers

Add the mod as a dependency and implement `StreamDeckedPlugin`. Your plugin declares layouts, and
StreamDecked groups them into a folder on the panel for you:

```java
public class ExampleDeckPlugin implements StreamDeckedPlugin {

    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("examplemod", "main");

    @Override
    public void registerLayouts(DeckLayoutRegistry registry) {
        DeckImage icon = DeckTextures.block(Blocks.REDSTONE_BLOCK);
        if (icon == null) return;   // register rejects a null icon

        registry.register(ID, icon, surface ->
                surface.setButton(0, DeckButton.text("Mute", 0xFFFFFFFF, 0xFF7A2020, this::toggleMute)));
    }
}
```

Ship the class in a `streamdecked.plugin.json` at the root of your jar and it is discovered at
startup:

```json
{
  "plugins": [
    {
      "class": "com.example.examplemod.ExampleDeckPlugin",
      "required_mods": ["examplemod"]
    }
  ]
}
```

`class` is required and must have a no-argument constructor. `required_mods` is optional, and a
plugin whose required mods are absent is skipped with a log line rather than an error, so you
can depend on another mod's API safely. A class that cannot be resolved is skipped the same way.

The plugin API also covers input events, page and folder navigation, icons with captions, and
per-deck settings. The
[example plugin](https://stream-decked.github.io/streamdecked/mod-developers/example.html)
walks through a complete one.

The event bus and `DeckTextures` come from the mod, not the library, so depend on the mod rather
than on [SD5J](https://github.com/Stream-Decked/SD5J) directly.

## Building from source

```bash
./gradlew build
```

The jar lands in `build/libs/`. Java 21 is required.

[SD5J](https://github.com/Stream-Decked/SD5J) is a normal Gradle dependency resolved from
Cloudsmith and embedded into the jar with JarJar, so players do not install it separately. Gson
and slf4j are excluded because Minecraft already ships them.

If you change the SD5J version, update `sd5j_version` in `gradle.properties` and the JarJar
version range together, or the mod will pull the wrong library at runtime.

To work on the library too, publish it to your local Maven first:

```bash
cd ../library && ./gradlew publishToMavenLocal
```

## Project information

- [Documentation](https://stream-decked.github.io/)
- [Licence](https://stream-decked.github.io/about-us/licence.html), Apache 2.0
- [AI stance](https://stream-decked.github.io/about-us/ai-stance.html)
