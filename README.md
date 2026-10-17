# PMonitor

PMonitor 在伺服器端錄下指定玩家收到的所有畫面資料，存成 [Flashback](https://modrinth.com/mod/flashback) 的回放檔。被錄的玩家不需要安裝任何東西，也不會察覺（預設不通知）。管理員把檔案放進自己電腦的 Flashback，就能用自由鏡頭從任何角度查看、慢動作播放，或直接輸出影片。

適合用來調查作弊、還原糾紛現場、錄製活動或宣傳影片。

### 功能特色

**完整的回放**
- 玩家動作、手持物品與裝備、挖掘裂痕、方塊變化、聊天、生物、盔甲座與名牌、天氣與時間、標題、王者血條、計分板、Tab 列表、藥水效果、地圖畫
- 死亡重生、切換維度、跨世界傳送（支援 Multiverse 等多世界插件）都不會中斷
- 每 5 分鐘自動建立快照，Flashback 可以快速跳到任何時間點

**隨時開始錄製**
- 玩家已經在線上也能立即開始，不需要重新進服
- 離線玩家可以設定「下次進服錄製」（只錄一次）或「每次進服自動錄製」
- 多位玩家可以同時錄製，各自產生獨立的檔案

**箱子介面**
- `/cpm` 開啟管理介面：玩家列表（含離線玩家，線上排前面）、搜尋、篩選
- 點選玩家即可開始／停止錄製、設定自動錄製、查看該玩家的錄製檔
- 錄製檔列表依時間排序，顯示長度、大小、版本、狀態，可在介面中刪除
- 只接受左鍵，防止誤觸與物品被拿走；Esc 回上一層

**安全存檔**
- 單一檔案超過 2 小時或 1 GB 自動切成新檔，錄製不中斷
- 正常關服時會等所有錄製存檔完成才關閉伺服器
- 當機或強制終止後，下次啟動自動修復未完成的錄製（通常只遺失最後約 1 秒）

**容量管理**
- 兩段式容量上限：超過提醒值（預設 5 GB）通知 OP 等級 2 以上的管理員並照常錄製；超過強停值（預設 10 GB）時所有錄製正確存檔後停止
- 刪除舊檔讓容量降回上限以下後，自動恢復錄製原本被停止的玩家
- 超過保留天數（預設 30 天）的錄製檔自動刪除

**效能**
- 實測 15 人同時錄製，主執行緒每 tick 約 0.2～0.4 ms（每位玩家約 0.02 ms）
- 快照分散在數個 tick 內完成，不會造成單次明顯卡頓
- 存檔、壓縮都在背景執行緒進行

**相容性**
- 一個 jar 支援 Paper 1.21.11、26.1、26.2、26.3
- 已測試：ViaVersion／ViaBackwards、Geyser／Floodgate（基岩版玩家）、Velocity、Multiverse-Core、PacketEvents、Paper 反礦透視
- 不需要任何前置插件

**其他**
- 下載連結（預設關閉）：開啟後可用 `/cpm download` 取得下載網址（預設 10 分鐘內有效）
- 啟動、`/cpm help`、`/cpm reload` 時自動檢查 GitHub 新版本（只提示，不自動下載）
- 所有訊息、介面文字都可在 `messages.yml` 修改；設定檔更新時自動保留原本的值並備份

### 指令與權限

主指令 `/pmonitor`，縮寫 `/cpm`。所有權限預設只有 OP 擁有。

| 指令 | 說明 | 權限 |
|---|---|---|
| `/cpm` | 開啟管理介面 | `pmonitor.use` |
| `/cpm help` | 版本、檢查更新、指令說明 | `pmonitor.help` |
| `/cpm start <玩家>` | 開始錄製；玩家離線時改為下次進服錄製 | `pmonitor.start` |
| `/cpm stop <玩家>` | 停止錄製並存檔；沒在錄時取消待命或關閉自動錄製 | `pmonitor.stop` |
| `/cpm status` | 錄製中與待命的玩家、目前容量 | `pmonitor.status` |
| `/cpm list [玩家]` | 錄製檔列表 | `pmonitor.list` |
| `/cpm download <檔名>` | 取得下載連結（需在設定中開啟） | `pmonitor.download` |
| `/cpm delete <檔名>` | 刪除錄製檔（玩家需在 30 秒內再輸入一次確認） | `pmonitor.delete` |
| `/cpm reload` | 重新載入設定並檢查更新 | `pmonitor.reload` |

| 其他權限 | 說明 |
|---|---|
| `pmonitor.admin` | 以上全部權限 |
| `pmonitor.status` | 同時接收錄製相關通知（開始失敗、自動分段等） |
| `pmonitor.update` | 進服時收到新版本通知 |

### 使用方式

**錄製一位玩家**
1. 輸入 `/cpm`，點選玩家的頭顱
2. 點「開始錄製」，要結束時再點一次「停止錄製」
3. 錄製檔存在 `plugins/PMonitor/recordings/<玩家 UUID>/<玩家>_<日期時間>.zip`

**持續監看可疑玩家**
1. `/cpm` → 點選玩家 → 開啟「每次進服自動錄製」
2. 之後該玩家每次上線都會自動錄製，離線時自動存檔

**用 Flashback 觀看並輸出 mp4**
1. 在電腦上安裝 [Fabric](https://fabricmc.net/use/installer/)，**Minecraft 版本要和伺服器相同**
2. 把 [Flashback](https://modrinth.com/mod/flashback) 和 [Fabric API](https://modrinth.com/mod/fabric-api) 放進 `.minecraft/mods/`
3. 把錄製檔（`.zip`，不用解壓縮）放進 `.minecraft/flashback/replays/`
4. 啟動遊戲 → 標題畫面的 Flashback 按鈕 → 選擇回放
5. 自由飛行、調整速度、設定鏡頭關鍵幀，用 Flashback 的匯出功能輸出 mp4

### 設定

| 設定 | 預設 | 說明 |
|---|---|---|
| `recording.notify-player` | `false` | 開始／停止錄製時是否通知被錄的玩家 |
| `recording.max-duration-minutes` | `120` | 單檔最長時間，超過自動切檔 |
| `recording.max-file-size-mb` | `1024` | 單檔最大容量（未壓縮），超過自動切檔 |
| `storage.warn-size-gb` | `5` | 總容量提醒值 |
| `storage.stop-size-gb` | `10` | 總容量強停值 |
| `storage.retention-days` | `30` | 保留天數 |
| `storage.warn-op-level` | `2` | 收到容量提醒的 OP 等級 |
| `download.enabled` | `false` | 是否開啟下載連結 |
| `update-checker.enabled` | `true` | 是否檢查更新 |

數值設 `0` 代表不限制。修改後執行 `/cpm reload`。

### 需求與注意事項

- Paper 1.21.11（Java 21 以上）或 Paper 26.1～26.3（Java 25）
- 回放檔需用與伺服器**相同 Minecraft 版本**的 Flashback 開啟
- 錄製檔壓縮後一般約每人每分鐘 0.6～1.1 MB；錄製中的檔案是未壓縮的，請預留暫存空間
- 被錄玩家自己的腳步聲、吃東西等音效不會出現在回放中（伺服器不會送給本人）
- 伺服器 TPS 低於 20 時，回放長度會比實際時間短
- 基岩版玩家在回放中顯示預設皮膚

---

PMonitor records, on the server side, everything a chosen player receives and saves it as a [Flashback](https://modrinth.com/mod/flashback) replay. The recorded player needs nothing installed and is not notified by default. Drop the file into Flashback on your own PC to watch from any angle with a free camera, play in slow motion, or export a video.

Great for investigating cheaters, reconstructing disputes, and capturing events or trailers.

> The in-game interface and messages are in Traditional Chinese. Every message can be translated in `messages.yml`.

### Features

**Complete replays**
- Player movement, held items and armor, block-breaking progress, block changes, chat, mobs, armor stands and name tags, weather and time, titles, boss bars, scoreboards, tab list, potion effects, maps
- Survives death and respawn, dimension changes and cross-world teleports (works with Multiverse and similar plugins)
- A snapshot every 5 minutes lets Flashback jump to any point quickly

**Start any time**
- Start recording a player who is already online, no rejoin needed
- For offline players: "record next join" (once) or "auto-record every join"
- Record several players at once, each to its own file

**Chest GUI**
- `/cpm` opens the manager: player list including offline players, search and filter
- Click a player to start/stop recording, toggle auto-record, or browse their recordings
- Recordings are listed by time with length, size, version and status, and can be deleted from the GUI
- Left-click only to prevent misclicks and item theft; Esc goes back

**Safe saving**
- Files longer than 2 hours or larger than 1 GB are split automatically without interrupting the recording
- On a normal shutdown the server waits until every recording is saved
- After a crash or forced kill, unfinished recordings are repaired on the next start (usually losing only the last second)

**Storage management**
- Two-tier limit: above the warning size (default 5 GB) OPs of level 2+ are notified and recording continues; above the stop size (default 10 GB) all recordings are saved properly and stopped
- Once old files are deleted and usage drops below the limit, the stopped players are recorded again automatically
- Recordings older than the retention period (default 30 days) are deleted automatically

**Performance**
- Measured with 15 players recorded at once: about 0.2–0.4 ms per tick on the main thread (about 0.02 ms per player)
- Snapshots are spread over several ticks to avoid lag spikes
- Saving and compression run on background threads

**Compatibility**
- One jar for Paper 1.21.11, 26.1, 26.2 and 26.3
- Tested with ViaVersion/ViaBackwards, Geyser/Floodgate (Bedrock players), Velocity, Multiverse-Core, PacketEvents and Paper anti-xray
- No dependencies

**More**
- Download links (off by default): `/cpm download` gives a URL valid for 10 minutes by default
- Checks GitHub for new versions on startup, `/cpm help` and `/cpm reload` (notification only)
- All messages and GUI text are editable in `messages.yml`; config updates keep your values and make a backup

### Commands & Permissions

Main command `/pmonitor`, alias `/cpm`. All permissions default to OP only.

| Command | Description | Permission |
|---|---|---|
| `/cpm` | Open the manager GUI | `pmonitor.use` |
| `/cpm help` | Version, update check, command list | `pmonitor.help` |
| `/cpm start <player>` | Start recording; for offline players, record on next join | `pmonitor.start` |
| `/cpm stop <player>` | Stop and save; if not recording, cancel the pending or auto recording | `pmonitor.stop` |
| `/cpm status` | Players being recorded or pending, current storage usage | `pmonitor.status` |
| `/cpm list [player]` | List recordings | `pmonitor.list` |
| `/cpm download <file>` | Get a download link (must be enabled in config) | `pmonitor.download` |
| `/cpm delete <file>` | Delete a recording (players must repeat the command within 30 s to confirm) | `pmonitor.delete` |
| `/cpm reload` | Reload config and check for updates | `pmonitor.reload` |

| Other permissions | Description |
|---|---|
| `pmonitor.admin` | All of the above |
| `pmonitor.status` | Also receive recording notifications (start failures, auto-split, etc.) |
| `pmonitor.update` | Get notified of new versions on join |

### How to use

**Record a player**
1. Run `/cpm` and click the player's head
2. Click "Start recording"; click again to stop
3. Recordings are saved to `plugins/PMonitor/recordings/<player UUID>/<player>_<date-time>.zip`

**Keep watching a suspicious player**
1. `/cpm` → click the player → turn on "Auto-record every join"
2. The player is recorded every time they join and the file is saved when they leave

**Watch in Flashback and export mp4**
1. Install [Fabric](https://fabricmc.net/use/installer/) on your PC — **same Minecraft version as the server**
2. Put [Flashback](https://modrinth.com/mod/flashback) and [Fabric API](https://modrinth.com/mod/fabric-api) in `.minecraft/mods/`
3. Put the recording (`.zip`, no need to extract) in `.minecraft/flashback/replays/`
4. Launch the game → Flashback button on the title screen → pick the replay
5. Fly around, change speed, set camera keyframes and export mp4 with Flashback

### Configuration

| Key | Default | Description |
|---|---|---|
| `recording.notify-player` | `false` | Notify the recorded player on start/stop |
| `recording.max-duration-minutes` | `120` | Max length per file before splitting |
| `recording.max-file-size-mb` | `1024` | Max uncompressed size per file before splitting |
| `storage.warn-size-gb` | `5` | Total storage warning size |
| `storage.stop-size-gb` | `10` | Total storage stop size |
| `storage.retention-days` | `30` | Days to keep recordings |
| `storage.warn-op-level` | `2` | OP level that receives storage warnings |
| `download.enabled` | `false` | Enable download links |
| `update-checker.enabled` | `true` | Check for updates |

`0` means unlimited. Run `/cpm reload` after editing.

### Requirements & notes

- Paper 1.21.11 (Java 21+) or Paper 26.1–26.3 (Java 25)
- Open replays with Flashback on the **same Minecraft version** as the server
- Compressed recordings are typically 0.6–1.1 MB per player per minute; recordings in progress are uncompressed, so leave some free space
- The recorded player's own footsteps and eating sounds are not in the replay (the server never sends them to that player)
- If the server runs below 20 TPS, the replay is shorter than real time
- Bedrock players appear with the default skin in replays
