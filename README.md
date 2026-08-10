# Maplestory Cosmic Solo

**English** | [中文 (机翻)](README.zh-CN.md)

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

**Added 5,000+ cosmetic items.** Hats, robes, weapons, shoes, capes, pet gear, accessories, rings,
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

## Setup

You need **Java 21** ([Amazon Corretto](https://aws.amazon.com/corretto) works), a **MySQL 8**
database, and a **MapleStory v83 client**. About 15 minutes.

### 1. Get the code

```
git clone https://github.com/cobaltdisco/Maplestory-Cosmic-Solo.git
cd Maplestory-Cosmic-Solo
```

### 2. Start a database

Easiest way, if you have Docker:

```
docker run -d --name cosmic-mysql -p 127.0.0.1:3306:3306 -e MYSQL_DATABASE=cosmic -e MYSQL_ALLOW_EMPTY_PASSWORD=yes mysql:8.0
```

That's an empty root password bound to your own machine only — fine for local play, not fine for
anything else.

If you'd rather install MySQL directly, create a database named `cosmic` and note your root
password.

### 3. Point the server at it

Open `config.yaml` and check the `server:` section near the bottom:

```yaml
DB_HOST: "localhost"
DB_USER: "root"
DB_PASS: ""          # your root password, or leave empty for the Docker command above
```

The server creates its own tables on first start.

### 4. Build and run

```
./mvnw.cmd clean package
java -Xmx2048m -Dwz-path=wz -jar target/Cosmic.jar
```

(`launch.bat` does the second line for you.) When the console says **"Cosmic is now online"**,
it's ready.

### 5. Connect the client

Get the client from [P0nk/Cosmic-client](https://github.com/P0nk/Cosmic-client) and follow its
README. It needs to point at `127.0.0.1`.

Log in with **admin / admin** — there's no PIN or PIC in this fork. Create a character and you're
in.

### 6. Open the admin panel

Go to **http://127.0.0.1:8686** in any browser while the server is running.

### One thing that won't work out of the box

The 5,000+ cosmetics and the extra hairstyles are in this repo as **server-side data only**. The
matching artwork lives in the client's own `Character.wz`, which is ~900 MB and isn't published
here. On a stock client the cash shop will list these items but won't draw them.

Rebuilding that file is possible with the tools below and a MapleLegends client.

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
