# The Birdhouse — RuneLite Plugin

A [RuneLite](https://runelite.net/) plugin for [The Birdhouse](https://thebirdhouse.games) — the free OSRS multiplayer bingo and board game platform.

## Features

### Drop Auto-Submit
Automatically detects when you receive a drop that matches a tile on your active Birdhouse game board. Submits proof instantly — no alt-tabbing or screenshot uploading needed.

### Achievement Tracking
Detects collection log additions, quest completions, level-ups, and pet drops. If any match a tile on your board, proof is auto-submitted.

### Screenshot Capture
Attaches an automatic screenshot at the moment of the drop for verification.

### Activity Tracking
Tracks your play sessions so your clan can see who's active. Reports login/logout and periodic heartbeats (every 5 minutes).

### In-Game Overlay
Shows your current tile progress (completed/remaining) in the top-left corner. Displays the last matched drop for 30 seconds.

### Sidebar Panel
Full board view in RuneLite's sidebar — see all your tiles, what's completed, and what's left. Click refresh to sync with the server.

### In-Game Notifications
Get a chat message when your proof is submitted and processed.

## Setup

1. Install the plugin from the RuneLite Plugin Hub (search "Birdhouse")
2. Open the plugin settings in RuneLite
3. Paste your **Auth Token** (found in your Birdhouse account settings)
4. Enter your **Room Code** (or leave blank if you only have one active game)
5. Play OSRS — matching drops are submitted automatically!

## Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| Auth Token | (empty) | Your Birdhouse authentication token |
| Room Code | (empty) | Active room code (auto-detects if blank) |
| Auto-Submit Drops | On | Automatically submit matching drops |
| Include Screenshot | On | Attach screenshot with submissions |
| Chat Notification | On | Show game chat message on submission |
| Show Overlay | On | Display tile progress on game screen |
| Track Play Sessions | On | Report session time to The Birdhouse |

## Supported Game Modes

- **Bingo** — marks tiles when you get the matching drop
- **Tile Race** — submits proof for your current tile
- **Territory War** — submits attack/fortify proofs
- **Chip Drop** — claims tiles on matching drops
- **Battleship Bingo** — submits attack proofs

## Building

Requires Java 11+ and Maven.

```bash
mvn clean package
```

## Links

- [The Birdhouse](https://thebirdhouse.games) — Play free OSRS bingo & board games
- [RuneLite](https://runelite.net/) — Open source OSRS client
- [RuneLite Plugin Hub](https://runelite.net/plugin-hub/) — Community plugins

## License

BSD 2-Clause — see [LICENSE](LICENSE)
