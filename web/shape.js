// Same 60% continuous-corner geometry as ContinuousShape.kt; no system icon or shape asset.
export function continuousCardPath(width, height, radius) {
  const r = Math.min(radius, width / 3.2, height / 3.2);
  const p = 1.6 * r;
  const arc = Math.PI / 5;
  const beta = (27 * Math.PI) / 180;
  const distance = r * Math.tan((13.5 * Math.PI) / 180);
  const c = distance * Math.cos(beta),
    d = c * Math.tan(beta);
  const b = (p - Math.sin(arc / 2) * r * Math.SQRT2 - c - d) / 3,
    a = 2 * b;
  const path = [`M ${p} 0`, `L ${width - p} 0`];
  const corner = (x, y, rotation) => {
    const point = (u, v) =>
      [
        [x + u, y + v],
        [x - v, y + u],
        [x - u, y - v],
        [x + v, y - u],
      ][rotation].join(' ');
    path.push(`C ${point(a, 0)} ${point(a + b, 0)} ${point(a + b + c, d)}`);
    const endAngle = -Math.PI / 2 + beta + arc;
    path.push(
      `A ${r} ${r} 0 0 1 ${point(p - r + r * Math.cos(endAngle), r + r * Math.sin(endAngle))}`,
    );
    path.push(`C ${point(p, p - a - b)} ${point(p, p - a)} ${point(p, p)}`);
  };
  corner(width - p, 0, 0);
  path.push(`L ${width} ${height - p}`);
  corner(width, height - p, 1);
  path.push(`L ${p} ${height}`);
  corner(p, height, 2);
  path.push(`L 0 ${p}`);
  corner(0, p, 3);
  path.push('Z');
  return path.join(' ');
}
