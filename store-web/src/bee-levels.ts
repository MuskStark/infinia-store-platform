/**
 * The bee ladder's visual identity (Infinia Level 标识): each hive level is a
 * honeycomb cell whose tier carries its own color, so the mark changes with the
 * level everywhere it is rendered — badges, the header chip, selects and the
 * account page all read from this table. The palette rides the JetBrains
 * marketplace tokens: blue baseline, green forager, orange guard, and the brand
 * gradient reserved for the queen.
 */
export type BeeTier = 'larva' | 'worker' | 'forager' | 'guard' | 'queen';

export type BeeMark = {
  tier: BeeTier;
  /** Fill of the honeycomb mark; the larva cell renders as an outline instead. */
  hex: string;
  /** The queen wears the brand gradient rather than a flat color. */
  gradient?: boolean;
};

export const BEE_LEVELS = [0, 1, 2, 3, 4] as const;

export const BEE_MARKS: Record<number, BeeMark> = {
  0: { tier: 'larva', hex: 'currentColor' }, // Larva — an empty outline cell
  1: { tier: 'worker', hex: '#0b70f5' }, // Worker — the hive's blue baseline
  2: { tier: 'forager', hex: '#21d789' }, // Forager — brings home the nectar
  3: { tier: 'guard', hex: '#fc801d' }, // Guard — defends the entrance
  4: { tier: 'queen', hex: '', gradient: true }, // Queen — wears the brand sweep
};

export function beeMark(level: number): BeeMark {
  return BEE_MARKS[Math.max(0, Math.min(4, level))] ?? BEE_MARKS[0];
}
