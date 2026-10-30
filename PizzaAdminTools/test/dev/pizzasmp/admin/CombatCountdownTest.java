// Copyright (c) 2025-2026 William W. (FolksyPizza).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package dev.pizzasmp.admin;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
public class CombatCountdownTest {
 public static void main(String[] args) {
  assert CombatCountdown.DURATION_MILLIS == 20_000L;
  assert CombatCountdown.secondsRemaining(20_000,0)==20;
  assert CombatCountdown.secondsRemaining(20_000,1)==20;
  assert CombatCountdown.secondsRemaining(20_000,1_000)==19;
  assert CombatCountdown.secondsRemaining(20_000,19_999)==1;
  assert CombatCountdown.secondsRemaining(20_000,20_000)==0;
  assert CombatCountdown.secondsRemaining(20_000,21_000)==0;
  TextComponent m=(TextComponent)CombatCountdown.message(20);
  assert m.content().equals("Combat: ");assert m.color().equals(NamedTextColor.WHITE);
  TextComponent time=(TextComponent)m.children().get(0);
  assert time.content().equals("20");assert time.color().value()==0x00BFFF;
  System.out.println("Combat countdown:11 unit assertions passed");
 }
}
