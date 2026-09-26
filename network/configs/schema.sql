-- SMP-Core network schema — single shared MariaDB for velocity + lobby + survival + maintenance.
--
-- This schema is the backbone of cross-server sync: because every backend and the
-- Velocity bridge read and write this one database, a player's inventory, balance,
-- settings, friends, ranks and last-known server are identical wherever they are.
--
-- AUTHORITY RULE: for any table PizzaNetworkCore creates at runtime, the definition
-- here is copied verbatim from PizzaNetworkCore's own ensureSchema() SQL. PNC's
-- CREATE TABLE IF NOT EXISTS calls are therefore no-ops against a database built from
-- this file. Do not "improve" column types here — a mismatch (e.g. DECIMAL vs DOUBLE
-- on auction price) will not error, it will silently round money.
--
-- Rebuilt 2026-08-06 for the 4-server topology (pvp retired, full fresh player wipe).

CREATE DATABASE IF NOT EXISTS pizzasmp CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE pizzasmp;

-- ---------------------------------------------------------------------------
-- SECTION 1 — Core identity and per-player state.
-- Owned by this file (PNC reads these but does not create them).
--
-- No FOREIGN KEYs to players(uuid) here, deliberately. Several write paths touch a
-- player's row while they are OFFLINE and possibly never-seen — most notably the
-- offline /pay path, which credits balances.pending_vault_credit for a target that
-- may not exist in players yet. Under a FK that insert throws and the payment is lost.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS players (
  uuid CHAR(36) PRIMARY KEY,
  username VARCHAR(16) NOT NULL,
  first_joined TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  last_seen TIMESTAMP NULL,
  playtime_seconds BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_players_username (username)
);

-- One row per online player. The Velocity bridge writes/heartbeats these and PNC
-- reads them to answer "who is online network-wide, and on which backend" — this is
-- what powers the global tablist and cross-server DM routing.
CREATE TABLE IF NOT EXISTS session_leases (
  uuid CHAR(36) PRIMARY KEY,
  lease_token CHAR(36) NOT NULL,
  server_name VARCHAR(32) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  heartbeat_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_session_expiry (expires_at),
  INDEX idx_session_server (server_name)
);

-- pending_vault_credit holds money owed to a player who was offline when paid;
-- PNC applies and zeroes it on their next join.
CREATE TABLE IF NOT EXISTS balances (
  uuid CHAR(36) PRIMARY KEY,
  money DECIMAL(18,2) NOT NULL DEFAULT 0,
  shards BIGINT NOT NULL DEFAULT 0,
  pending_vault_credit DECIMAL(18,2) NOT NULL DEFAULT 0,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- PNC stores all /settings toggles as one serialised blob rather than columns, so
-- adding a toggle needs no migration. The legacy boolean columns are kept for the
-- older modular plugins that still read them.
CREATE TABLE IF NOT EXISTS player_settings (
  uuid CHAR(36) PRIMARY KEY,
  settings_blob LONGTEXT NULL,
  fast_auction_buying TINYINT(1) NOT NULL DEFAULT 0,
  private_messages TINYINT(1) NOT NULL DEFAULT 1,
  payment_requests TINYINT(1) NOT NULL DEFAULT 1,
  trade_requests TINYINT(1) NOT NULL DEFAULT 1,
  duel_requests TINYINT(1) NOT NULL DEFAULT 1,
  combat_notifications TINYINT(1) NOT NULL DEFAULT 1,
  auction_notifications TINYINT(1) NOT NULL DEFAULT 1,
  orders_notifications TINYINT(1) NOT NULL DEFAULT 1,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS player_stats (
  uuid CHAR(36) PRIMARY KEY,
  kills INT NOT NULL DEFAULT 0,
  deaths INT NOT NULL DEFAULT 0,
  blocks_placed BIGINT NOT NULL DEFAULT 0,
  blocks_broken BIGINT NOT NULL DEFAULT 0,
  mobs_killed BIGINT NOT NULL DEFAULT 0,
  shop_spent DECIMAL(18,2) NOT NULL DEFAULT 0,
  sell_earned DECIMAL(18,2) NOT NULL DEFAULT 0,
  duel_wins INT NOT NULL DEFAULT 0,
  duel_losses INT NOT NULL DEFAULT 0,
  auctions_bought INT NOT NULL DEFAULT 0,
  auctions_sold INT NOT NULL DEFAULT 0,
  orders_created INT NOT NULL DEFAULT 0,
  orders_fulfilled INT NOT NULL DEFAULT 0,
  punishments_count INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS homes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  uuid CHAR(36) NOT NULL,
  home_name VARCHAR(24) NOT NULL,
  world_name VARCHAR(64) NOT NULL,
  x DOUBLE NOT NULL,
  y DOUBLE NOT NULL,
  z DOUBLE NOT NULL,
  yaw FLOAT NOT NULL,
  pitch FLOAT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_home (uuid, home_name)
);

CREATE TABLE IF NOT EXISTS warps (
  warp_key VARCHAR(32) PRIMARY KEY,
  server_name VARCHAR(32) NOT NULL,
  world_name VARCHAR(64) NOT NULL,
  x DOUBLE NOT NULL,
  y DOUBLE NOT NULL,
  z DOUBLE NOT NULL,
  yaw FLOAT NOT NULL,
  pitch FLOAT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS rtp_cooldowns (
  uuid CHAR(36) NOT NULL,
  world_key VARCHAR(16) NOT NULL,
  next_allowed_at TIMESTAMP NOT NULL,
  PRIMARY KEY (uuid, world_key)
);

-- ---------------------------------------------------------------------------
-- SECTION 2 — Moderation. Owned by PizzaPunishment / this file.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS punishments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  target_uuid CHAR(36) NOT NULL,
  target_name VARCHAR(16) NOT NULL,
  actor_uuid CHAR(36) NULL,
  actor_name VARCHAR(16) NOT NULL,
  type ENUM('BAN','MUTE','KICK','WARN') NOT NULL,
  reason VARCHAR(255) NOT NULL,
  issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NULL,
  revoked_at TIMESTAMP NULL,
  revoked_by VARCHAR(16) NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  drop_on_next_join TINYINT(1) NOT NULL DEFAULT 0,
  dropped_inventory TINYINT(1) NOT NULL DEFAULT 0,
  drop_context VARCHAR(64) NULL,
  INDEX idx_punish_target (target_uuid),
  INDEX idx_punish_active (active),
  INDEX idx_punish_target_active (target_uuid, active)
);

CREATE TABLE IF NOT EXISTS punishment_audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  punishment_id BIGINT NOT NULL,
  action VARCHAR(64) NOT NULL,
  actor_name VARCHAR(16) NOT NULL,
  details TEXT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (punishment_id) REFERENCES punishments(id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- SECTION 3 — Network transfer / maintenance control plane.
-- Owned by this file; PNC's MaintenanceQueueManager and the Velocity bridge read it.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS shared_inventories (
  uuid CHAR(36) PRIMARY KEY,
  inventory_blob MEDIUMBLOB NOT NULL,
  enderchest_blob MEDIUMBLOB NOT NULL,
  armor_blob MEDIUMBLOB NULL,
  offhand_blob BLOB NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- targets_csv defaults to 'survival' — the pvp backend was retired 2026-08-06 and the
-- lobby is the offload destination, so it is never itself a maintenance target.
CREATE TABLE IF NOT EXISTS maintenance_state (
  id TINYINT PRIMARY KEY,
  active TINYINT(1) NOT NULL DEFAULT 0,
  targets_csv VARCHAR(255) NOT NULL DEFAULT 'survival',
  message VARCHAR(255) NOT NULL DEFAULT 'Maintenance in progress. Please wait in lobby.',
  started_at TIMESTAMP NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS transfer_locks (
  uuid CHAR(36) PRIMARY KEY,
  from_server VARCHAR(32) NOT NULL,
  to_server VARCHAR(32) NOT NULL,
  token CHAR(36) NOT NULL,
  state ENUM('SAVING','SAVED','TRANSFERRED','LOADED','FAILED') NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS duel_matches (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_a_uuid CHAR(36) NOT NULL,
  player_b_uuid CHAR(36) NOT NULL,
  winner_uuid CHAR(36) NOT NULL,
  loser_uuid CHAR(36) NOT NULL,
  started_at TIMESTAMP NOT NULL,
  ended_at TIMESTAMP NOT NULL,
  mode VARCHAR(32) NOT NULL DEFAULT 'BYO',
  elo_delta INT NULL
);

-- ---------------------------------------------------------------------------
-- SECTION 4 — Tables owned by PizzaNetworkCore.
-- Copied verbatim from PNC's ensureSchema(). See AUTHORITY RULE at the top.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS anticheat_violations (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_uuid CHAR(36) NOT NULL,
  player_name VARCHAR(16) NOT NULL,
  violation_type VARCHAR(64) NOT NULL,
  severity VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
  description TEXT NULL,
  check_name VARCHAR(96) NULL,
  vl_points DOUBLE NOT NULL DEFAULT 0,
  plugin_name VARCHAR(32) NOT NULL DEFAULT 'GrimAC',
  server_name VARCHAR(32) NOT NULL,
  reported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  reviewed TINYINT(1) NOT NULL DEFAULT 0,
  reviewed_by CHAR(36) NULL,
  reviewed_at TIMESTAMP NULL,
  action_taken VARCHAR(32) NULL,
  action_details TEXT NULL,
  INDEX idx_violation_player (player_uuid, reported_at),
  INDEX idx_violation_reviewed (reviewed, reported_at),
  INDEX idx_violation_severity (severity, reported_at)
);

CREATE TABLE IF NOT EXISTS auction_listings (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  seller_uuid CHAR(36) NOT NULL,
  buyer_uuid CHAR(36) NULL,
  item_blob LONGBLOB NOT NULL,
  item_key VARCHAR(96) NOT NULL,
  price DOUBLE NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  expires_at TIMESTAMP NULL DEFAULT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_auction_status_created (status, created_at),
  INDEX idx_auction_seller_status (seller_uuid, status)
);

CREATE TABLE IF NOT EXISTS auction_payouts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  recipient_uuid CHAR(36) NOT NULL,
  listing_id BIGINT NOT NULL,
  amount DOUBLE NOT NULL,
  claimed TINYINT(1) NOT NULL DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_auction_payout_recipient (recipient_uuid, claimed)
);

CREATE TABLE IF NOT EXISTS bounties (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  placer_uuid CHAR(36) NOT NULL,
  target_uuid CHAR(36) NOT NULL,
  amount DOUBLE NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  claimer_uuid CHAR(36) NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  claimed_at TIMESTAMP NULL,
  INDEX idx_bounty_target (target_uuid, status),
  INDEX idx_bounty_placer (placer_uuid, status)
);

CREATE TABLE IF NOT EXISTS command_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_uuid CHAR(36) NOT NULL,
  player_name VARCHAR(16) NOT NULL,
  command TEXT NOT NULL,
  server_name VARCHAR(32) NOT NULL,
  logged_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_cmd_player (player_uuid, logged_at),
  INDEX idx_cmd_logged (logged_at)
);

CREATE TABLE IF NOT EXISTS crate_reward_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  uuid CHAR(36) NOT NULL,
  username VARCHAR(16) NOT NULL,
  crate_key VARCHAR(32) NOT NULL,
  shards_cost INT NOT NULL,
  reward_count INT NOT NULL,
  server_name VARCHAR(32) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_crate_uuid (uuid),
  INDEX idx_crate_created (created_at)
);

CREATE TABLE IF NOT EXISTS death_chests (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_uuid CHAR(36) NOT NULL,
  player_name VARCHAR(16) NOT NULL,
  world_name VARCHAR(64) NOT NULL,
  x DOUBLE NOT NULL,
  y DOUBLE NOT NULL,
  z DOUBLE NOT NULL,
  items_blob LONGBLOB NOT NULL,
  xp_levels INT NOT NULL DEFAULT 0,
  xp_points FLOAT NOT NULL DEFAULT 0,
  claimed TINYINT(1) NOT NULL DEFAULT 0,
  killed_by_name VARCHAR(16) NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  claimed_at TIMESTAMP NULL,
  INDEX idx_death_chest_unclaimed (player_uuid, claimed)
);

CREATE TABLE IF NOT EXISTS follows (
  follower_uuid CHAR(36) NOT NULL,
  target_uuid CHAR(36) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  see_activity TINYINT(1) NOT NULL DEFAULT 1,
  see_transactions TINYINT(1) NOT NULL DEFAULT 1,
  can_message TINYINT(1) NOT NULL DEFAULT 1,
  can_tpa TINYINT(1) NOT NULL DEFAULT 1,
  auto_accept_tpa TINYINT(1) NOT NULL DEFAULT 1,
  can_pay TINYINT(1) NOT NULL DEFAULT 1,
  PRIMARY KEY (follower_uuid, target_uuid),
  INDEX idx_follow_target (target_uuid)
);

CREATE TABLE IF NOT EXISTS friends (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  requester_uuid CHAR(36) NOT NULL,
  target_uuid CHAR(36) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  responded_at TIMESTAMP NULL DEFAULT NULL,
  UNIQUE KEY uk_friend_pair (requester_uuid,target_uuid),
  INDEX idx_friend_target_status (target_uuid,status),
  INDEX idx_friend_requester_status (requester_uuid,status)
);

CREATE TABLE IF NOT EXISTS limbo_control (
  id TINYINT PRIMARY KEY,
  return_all_at TIMESTAMP NULL DEFAULT NULL,
  smp_ready_at TIMESTAMP NULL DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS limbo_snapshots (
  uuid CHAR(36) PRIMARY KEY,
  world VARCHAR(64) NOT NULL,
  x DOUBLE NOT NULL,
  y DOUBLE NOT NULL,
  z DOUBLE NOT NULL,
  yaw FLOAT NOT NULL,
  pitch FLOAT NOT NULL,
  inv_blob LONGBLOB NULL,
  ender_blob LONGBLOB NULL,
  blocks_blob LONGBLOB NULL,
  captured_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS maintenance_queue (
  uuid CHAR(36) PRIMARY KEY,
  player_name VARCHAR(16) NOT NULL,
  desired_server VARCHAR(32) NOT NULL DEFAULT 'lobby',
  desired_reason VARCHAR(64) NOT NULL DEFAULT 'maintenance',
  priority INT NOT NULL DEFAULT 100,
  attempts INT NOT NULL DEFAULT 0,
  last_attempt_at TIMESTAMP NULL,
  status ENUM('WAITING','RETURNED') NOT NULL DEFAULT 'WAITING',
  enqueued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS maintenance_return_queue (
  uuid CHAR(36) PRIMARY KEY,
  username_snapshot VARCHAR(32) NOT NULL,
  desired_server VARCHAR(32) NOT NULL,
  desired_reason VARCHAR(64) NOT NULL DEFAULT 'maintenance',
  attempts INT NOT NULL DEFAULT 0,
  last_attempt_at TIMESTAMP NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS network_chat_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  logged_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  player_uuid CHAR(36) NOT NULL,
  player_name VARCHAR(16) NOT NULL,
  server_name VARCHAR(32) NOT NULL,
  channel VARCHAR(16) NOT NULL DEFAULT 'global',
  message TEXT NOT NULL,
  source_plugin VARCHAR(32) NOT NULL DEFAULT 'PizzaNetworkCore',
  INDEX idx_chat_uuid (player_uuid),
  INDEX idx_chat_server (server_name),
  INDEX idx_chat_channel (channel),
  INDEX idx_chat_logged_at (logged_at)
);

CREATE TABLE IF NOT EXISTS network_player_notifications (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  recipient_uuid CHAR(36) NOT NULL,
  message TEXT NOT NULL,
  action VARCHAR(96) NOT NULL DEFAULT '',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NOT NULL,
  INDEX idx_notify_recipient (recipient_uuid, expires_at)
);

CREATE TABLE IF NOT EXISTS network_teleport_requests (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  requester_uuid CHAR(36) NOT NULL,
  requester_name VARCHAR(16) NOT NULL,
  target_uuid CHAR(36) NOT NULL,
  target_name VARCHAR(16) NOT NULL,
  request_type VARCHAR(16) NOT NULL,
  requester_server VARCHAR(32) NOT NULL,
  target_server VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NOT NULL,
  responded_at TIMESTAMP NULL DEFAULT NULL,
  INDEX idx_tp_target_pending (target_uuid, status, expires_at),
  INDEX idx_tp_requester_pending (requester_uuid, status, expires_at)
);

CREATE TABLE IF NOT EXISTS order_deliveries (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  fulfiller_uuid CHAR(36) NOT NULL,
  amount INT NOT NULL,
  payout DOUBLE NOT NULL,
  item_blob LONGBLOB NULL,
  claimed TINYINT(1) NOT NULL DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  claimed_at TIMESTAMP NULL DEFAULT NULL,
  INDEX idx_order_delivery_order (order_id),
  INDEX idx_order_delivery_claimed (claimed)
);

CREATE TABLE IF NOT EXISTS order_listings (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  creator_uuid CHAR(36) NOT NULL,
  item_key VARCHAR(96) NOT NULL,
  amount_total INT NOT NULL,
  amount_filled INT NOT NULL DEFAULT 0,
  unit_price DOUBLE NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NULL DEFAULT NULL,
  INDEX idx_order_status_created (status, created_at),
  INDEX idx_order_creator_status (creator_uuid, status)
);

CREATE TABLE IF NOT EXISTS player_logout_meta (
  uuid CHAR(36) PRIMARY KEY,
  last_logout_server VARCHAR(32) NOT NULL,
  last_logout_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS player_sync_state (
  uuid CHAR(36) PRIMARY KEY,
  last_server VARCHAR(32) NOT NULL,
  world_name VARCHAR(64) NULL,
  x DOUBLE NULL,
  y DOUBLE NULL,
  z DOUBLE NULL,
  yaw FLOAT NULL,
  pitch FLOAT NULL,
  game_mode VARCHAR(16) NOT NULL DEFAULT 'SURVIVAL',
  inventory_blob LONGBLOB NOT NULL,
  enderchest_blob LONGBLOB NOT NULL,
  stats_blob LONGBLOB NULL,
  health DOUBLE NOT NULL DEFAULT 20,
  food_level INT NOT NULL DEFAULT 20,
  saturation FLOAT NOT NULL DEFAULT 20,
  exhaustion FLOAT NOT NULL DEFAULT 0,
  exp FLOAT NOT NULL DEFAULT 0,
  level INT NOT NULL DEFAULT 0,
  total_experience INT NOT NULL DEFAULT 0,
  allow_flight TINYINT(1) NOT NULL DEFAULT 0,
  is_flying TINYINT(1) NOT NULL DEFAULT 0,
  fly_speed FLOAT NOT NULL DEFAULT 0.1,
  walk_speed FLOAT NOT NULL DEFAULT 0.2,
  fire_ticks INT NOT NULL DEFAULT 0,
  remaining_air INT NOT NULL DEFAULT 300,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS player_transfer_actions (
  uuid CHAR(36) PRIMARY KEY,
  action VARCHAR(255) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sell_multiplier_progress (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_uuid CHAR(36) NOT NULL,
  category VARCHAR(32) NOT NULL,
  level INT NOT NULL DEFAULT 0,
  earned DECIMAL(18,2) NOT NULL DEFAULT 0,
  claimed_up_to INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_sellmulti (player_uuid, category)
);

CREATE TABLE IF NOT EXISTS shard_daily_claims (
  uuid CHAR(36) PRIMARY KEY,
  last_claim TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS team_invites (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  team_id BIGINT NOT NULL,
  invitee_uuid CHAR(36) NOT NULL,
  invited_by_uuid CHAR(36) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NOT NULL,
  UNIQUE KEY uk_invite (team_id,invitee_uuid),
  INDEX idx_invite_invitee (invitee_uuid)
);

CREATE TABLE IF NOT EXISTS team_members (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  team_id BIGINT NOT NULL,
  member_uuid CHAR(36) NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'member',
  team_chat_mode TINYINT(1) NOT NULL DEFAULT 0,
  joined_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_team_member (team_id,member_uuid),
  INDEX idx_team_member_uuid (member_uuid)
);

CREATE TABLE IF NOT EXISTS teams (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  team_name VARCHAR(32) NOT NULL,
  owner_uuid CHAR(36) NOT NULL,
  pvp_enabled TINYINT(1) NOT NULL DEFAULT 0,
  home_world VARCHAR(64) NULL,
  home_x DOUBLE NULL,
  home_y DOUBLE NULL,
  home_z DOUBLE NULL,
  home_yaw FLOAT NULL,
  home_pitch FLOAT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_team_name (team_name),
  INDEX idx_teams_owner (owner_uuid)
);

CREATE TABLE IF NOT EXISTS transaction_audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_uuid CHAR(36) NOT NULL,
  player_name VARCHAR(16) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  item_key VARCHAR(96) NOT NULL,
  amount INT NOT NULL DEFAULT 1,
  unit_price DOUBLE NOT NULL DEFAULT 0,
  total_price DOUBLE NOT NULL DEFAULT 0,
  extra_info VARCHAR(255) NULL,
  server_name VARCHAR(32) NOT NULL,
  logged_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_audit_player (player_uuid, logged_at),
  INDEX idx_audit_event (event_type, logged_at)
);
-- Seed the singleton maintenance row so the control plane always has state to read.
INSERT IGNORE INTO maintenance_state (id, active, targets_csv) VALUES (1, 0, 'survival');
