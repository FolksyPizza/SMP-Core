// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the SMP-Core Source License, version 1.0 (see LICENSE). All rights reserved.
rootProject.name = "SMP-Core"

// Every plugin directory is a Gradle project; PizzaCommon is shared source compiled into the plugins that use it.
include(
    "PizzaAdminTools",
    "PizzaChatGuard",
    "PizzaEnderchest",
    "PizzaLimbo",
    "PizzaNetworkCore",
    "PizzaProxyGuard",
    "PizzaPunishment",
    "PizzaRuleGuard",
    "PizzaSpawnRules",
    "PizzaTune",
)
