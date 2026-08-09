# Maplestory Cosmic Solo

**English** | [中文（机翻）](README.zh-CN.md)

A fork of **[P0nk/Cosmic](https://github.com/P0nk/Cosmic)** (a MapleStory v83 server), set up for
playing alone on your own computer. No network, no other players.

The original Cosmic is a multiplayer server. This version is just for one person, which often means
changing things in the opposite direction — hence the fork.

> **This is not upstream Cosmic.** Bugs here are mine, not Ponk's or the Cosmic contributors'.
> **Please don't report them upstream.**

---

## ⚠️ Read this first

The admin panel has no password — not a weak one, none at all. Anyone who opens it can give
themselves any item, change the rates, teleport, kick players.

It only runs on your own machine (`127.0.0.1:8686`) and can't be reached from outside without
changing the code. Don't expose it. If you don't want it, set `WEB_ADMIN_ENABLED` to `false` in
`config.yaml`.

Docker won't work — the server is locked to localhost and Docker can't reach it. Run directly.

---

## What's changed

**Locked to single player.** Only your own computer can connect. PIN/PIC removed, one channel
instead of three (faster startup), rates set to 8× exp, 5× meso, 3× drop.

**Added a web admin panel.** Opens in your browser, nothing else to install.

Give yourself any item or equip — search by name, category, or which monster drops it. Sell gear at
shop prices. Adjust fame. Click the world map to teleport (hidden maps too). If your character gets
stuck, kick the session without restarting the server.

Auto-play is also there: auto attack, auto potion (set your threshold), auto loot (with filters),
auto buff, and mob pulling. Plus drop lookups — what drops what, which monsters are where.

**Added 5,872 cosmetic items.** Hats, robes, weapons, shoes, capes, pet gear, accessories, rings,
gloves and more — converted from a MapleLegends client and put in the cash shop with names and
stats filled in. Also fixed 360 item names that were cut off and pulled a few that crashed the
client.

**Added 100+ new hairstyles and eyes to the beauty salon.** Sorted by gender, with ones that don't
render filtered out. All skin tones available.

**Fixed bugs in the original:**

- Monster debuffs (poison, stun, seal, etc.) never worked — a rounding bug made 86 debuffs across
  179 monsters impossible to trigger. Fixed.
- Some quests showed as available but the server silently refused them. Fixed for quest 8255, five
  repeatables, and Adonis's daily.
- Pet skill quests didn't give you the skill. Fixed.
- Zakum no longer requires six people.
- 89 maps had misaligned ground. Fixed.
- Seasonal NPCs no longer hang around all year. Unrenderable statues removed. Boss HP bars only
  show when they work. Character no longer freezes on an empty pickup.

**Save failures now show an error** instead of silently losing progress. Two memory leaks fixed.

---

## Tools

**[Maplestory-Cosmic-Solo-Tools](https://github.com/cobaltdisco/Maplestory-Cosmic-Solo-Tools)** —
82 small utilities (MIT) for reading and writing game files, extracting art, comparing data, and
diagnosing crashes. No game data included.

---

## Not included

- **Admin panel images.** About 16,400 item icons and monster sprites from the client. Regenerate
  with the tools above.
- **The game client.** Not here, not coming.

---

## Licence

AGPL-3.0, same as upstream. Most of this code was written by others — from OdinMS (2008) through
HeavenMS (2019) to Cosmic, maintained by Ponk.

No support, no issue tracking. Use at your own risk.
