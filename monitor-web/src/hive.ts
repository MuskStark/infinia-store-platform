const HEX_WIDTH = 200;
const HEX_HEIGHT = HEX_WIDTH * 2 / Math.sqrt(3);
const DAY_WIDTH = 19.7;
const DAY_HEIGHT = DAY_WIDTH * 2 / Math.sqrt(3);
const daySlots: { x: number; y: number }[] = [];
const fillerSlots: { x: number; y: number }[] = [];
const vertices = [[0, -0.5], [0.5, -0.25], [0.5, 0.25], [0, 0.5], [-0.5, 0.25], [-0.5, -0.25]];
for (let row = -8; row <= 7; row++) {
  for (let col = -6; col <= 5; col++) {
    const x = (col + (Math.abs(row) % 2) / 2 + 0.5) * DAY_WIDTH;
    const y = (row + 0.25) * DAY_HEIGHT * 0.75;
    fillerSlots.push({ x: x + HEX_WIDTH / 2, y: y + HEX_HEIGHT / 2 });
    if (vertices.every(([dx, dy]) => {
      const vx = Math.abs(x + dx * DAY_WIDTH);
      const vy = Math.abs(y + dy * DAY_HEIGHT);
      return vx <= HEX_WIDTH / 2 && vx / (HEX_WIDTH / 2) + 2 * vy / (HEX_HEIGHT / 2) <= 2;
    })) {
      daySlots.push({ x: x + HEX_WIDTH / 2, y: y + HEX_HEIGHT / 2 });
    }
  }
}


export { daySlots, HEX_WIDTH, HEX_HEIGHT, DAY_WIDTH, DAY_HEIGHT };
