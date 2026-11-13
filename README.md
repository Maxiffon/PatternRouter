# Pattern Router

Side panel for the AE2 pattern terminal in GT: New Horizons. After you encode a processing pattern, it lists the ME
interfaces and CRIBs whose GregTech machine can run that recipe. One click puts the pattern there.

![logo](src/main/resources/assets/patternrouter/logo.png)

## Features
- **Machine match by recipe map**, not by name. Renamed interfaces still work.
- **Mold / circuit / lens check.**
  - Rows are marked *fits* when the machine holds the required non-consumed item, *doesn't fit* when it doesn't.
  - Where it looks: CRIB manual and circuit slots, the single-block machine next to an ME interface, or an input bus.
- **Works without NEI.** If the pattern was not transferred from NEI, the server runs a real GT recipe lookup with the
  pattern inputs plus what each machine holds.
- **Conflicts.** Shows patterns elsewhere in the network that already produce the same output, with an *Extract*
  button that moves the old pattern to your inventory.
- **Respects AE2 security** (build permission).
- **NEI makes room** for the panel (`INEIGuiAdapter`).
- **AE2 GUIs are not patched.** The panel uses Forge GUI events only.

## Requirements
Client **and** server. Needs AE2 (GTNH fork), GT5-Unofficial and NotEnoughItems. Built against GTNH 2.9.0-RC-2.

If only one side has the mod, players can still connect; the panel just finds nothing.

## Config
`config/patternrouter.cfg`: `panelSide = right | left`.

## Building
```
gradlew build
```
Java 21 (Jabel, targets Java 8).

## Credits
Idea from GregTech Odyssey (gtocore) and AE2 Auto Pattern Upload (GaLicn). No code was copied.
