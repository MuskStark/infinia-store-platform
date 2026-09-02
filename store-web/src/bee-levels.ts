/**
 * The bee ladder's visual identity (Infinia Level 标识): each hive level carries its
 * own emblem and tone, so the mark changes with the level everywhere it is
 * rendered — badges, selects and the account page all read from this table.
 */
export type BeeMark = { emblem: string; tone: 'muted' | 'accent' | 'success' | 'danger' | 'gold' };

export const BEE_LEVELS = [0, 1, 2, 3, 4] as const;

export const BEE_MARKS: Record<number, BeeMark> = {
  0: { emblem: '🥚', tone: 'muted' }, // Larva — still in the cell
  1: { emblem: '🐝', tone: 'accent' }, // Worker — the hive's baseline
  2: { emblem: '🍯', tone: 'success' }, // Forager — brings home nectar
  3: { emblem: '🛡️', tone: 'danger' }, // Guard — defends the entrance
  4: { emblem: '👑', tone: 'gold' }, // Queen — rules the hive
};

export function beeMark(level: number): BeeMark {
  return BEE_MARKS[Math.max(0, Math.min(4, level))] ?? BEE_MARKS[0];
}
