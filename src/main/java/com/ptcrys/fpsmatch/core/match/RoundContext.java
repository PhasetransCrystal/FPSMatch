package com.ptcrys.fpsmatch.core.match;

/**
 * 回合生命周期每 tick 可携带的上下文标记接口。
 * 具体模式可扩展此接口注入自定义状态（如 C4 状态、队伍存活等）。
 */
public interface RoundContext {}
