// Splits the Mac app's icon into the three layers of an Android adaptive icon.
//
//   swift -O scripts/launcher-icon-layers.swift <master.png> <out-dir>
//
// Writes background.png, foreground.png and monochrome.png to <out-dir>, each 108 dp square at
// 16 px/dp (1728 px). scripts/import-launcher-icon.sh runs it and scales the layers to each
// density; run that rather than this.
//
// The Mac icon is one picture: a glyph on a textured grey gradient inside a macOS rounded square.
// Android masks the shape itself, so the picture is taken apart:
// - background: the grey gradient, fitted smoothly to the pixels around the glyph and carried
//   under it and out to the edges of the canvas;
// - foreground: whatever differs from that gradient (the glyph and its shadow), with the alpha
//   chosen so foreground over background gives back the original pixels;
// - monochrome: the glyph's silhouette for themed icons, with the eyes and the prompt cut out.
// The glyph is scaled so the smallest circle around it is the 66 dp safe zone, so no launcher
// mask clips it.

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let canvasSize = 1728
let pxPerDp: Float = 16
let safeZoneRadiusDp: Float = 33

func fail(_ message: String) -> Never {
  FileHandle.standardError.write(Data("launcher-icon-layers: \(message)\n".utf8))
  exit(1)
}

// MARK: Pixels

struct Picture {
  let width: Int
  let height: Int
  var red: [Float]
  var green: [Float]
  var blue: [Float]
  var alpha: [Float]

  var count: Int { width * height }

  func channel(_ c: Int) -> [Float] { c == 0 ? red : c == 1 ? green : blue }
}

func load(_ url: URL) -> Picture {
  guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
    let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
  else { fail("can't read \(url.path)") }
  let width = image.width
  let height = image.height
  var bytes = [UInt8](repeating: 0, count: width * height * 4)
  let drawn = bytes.withUnsafeMutableBytes { raw -> Bool in
    guard
      let context = CGContext(
        data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8,
        bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
    else { return false }
    context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
    return true
  }
  guard drawn else { fail("can't decode \(url.path)") }
  let count = width * height
  var picture = Picture(
    width: width, height: height, red: .init(repeating: 0, count: count),
    green: .init(repeating: 0, count: count), blue: .init(repeating: 0, count: count),
    alpha: .init(repeating: 0, count: count))
  for i in 0..<count {
    let a = Float(bytes[i * 4 + 3]) / 255
    picture.alpha[i] = a
    guard a > 0 else { continue }
    picture.red[i] = Float(bytes[i * 4]) / a
    picture.green[i] = Float(bytes[i * 4 + 1]) / a
    picture.blue[i] = Float(bytes[i * 4 + 2]) / a
  }
  return picture
}

/// Writes premultiplied RGBA (0...1) as an sRGB PNG.
func write(_ rgba: [Float], size: Int, opaque: Bool, to url: URL) {
  var bytes = [UInt8](repeating: 0, count: size * size * 4)
  for i in 0..<rgba.count { bytes[i] = UInt8((min(max(rgba[i], 0), 1) * 255).rounded()) }
  let info = opaque ? CGImageAlphaInfo.noneSkipLast : CGImageAlphaInfo.premultipliedLast
  let written = bytes.withUnsafeMutableBytes { raw -> Bool in
    guard
      let context = CGContext(
        data: raw.baseAddress, width: size, height: size, bitsPerComponent: 8,
        bytesPerRow: size * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: info.rawValue),
      let image = context.makeImage(),
      let destination = CGImageDestinationCreateWithURL(
        url as CFURL, UTType.png.identifier as CFString, 1, nil)
    else { return false }
    CGImageDestinationAddImage(destination, image, nil)
    return CGImageDestinationFinalize(destination)
  }
  guard written else { fail("can't write \(url.path)") }
}

func bilinear(_ plane: [Float], width: Int, height: Int, x: Float, y: Float, outside: Float?)
  -> Float
{
  if let outside, x < -0.5 || y < -0.5 || x > Float(width) - 0.5 || y > Float(height) - 0.5 {
    return outside
  }
  let fx = min(max(x, 0), Float(width - 1))
  let fy = min(max(y, 0), Float(height - 1))
  let x0 = Int(fx)
  let y0 = Int(fy)
  let x1 = min(x0 + 1, width - 1)
  let y1 = min(y0 + 1, height - 1)
  let tx = fx - Float(x0)
  let ty = fy - Float(y0)
  let top = plane[y0 * width + x0] * (1 - tx) + plane[y0 * width + x1] * tx
  let bottom = plane[y1 * width + x0] * (1 - tx) + plane[y1 * width + x1] * tx
  return top * (1 - ty) + bottom * ty
}

func smoothstep(_ edge0: Float, _ edge1: Float, _ x: Float) -> Float {
  let t = min(max((x - edge0) / (edge1 - edge0), 0), 1)
  return t * t * (3 - 2 * t)
}

// MARK: Masks

/// The mask with every region it encloses filled in.
func fillHoles(_ mask: [Bool], width: Int, height: Int) -> [Bool] {
  var outside = [Bool](repeating: false, count: mask.count)
  var stack: [Int] = []
  func visit(_ i: Int) {
    if !mask[i] && !outside[i] {
      outside[i] = true
      stack.append(i)
    }
  }
  for x in 0..<width {
    visit(x)
    visit((height - 1) * width + x)
  }
  for y in 0..<height {
    visit(y * width)
    visit(y * width + width - 1)
  }
  while let i = stack.popLast() {
    let x = i % width
    if x > 0 { visit(i - 1) }
    if x < width - 1 { visit(i + 1) }
    if i >= width { visit(i - width) }
    if i < mask.count - width { visit(i + width) }
  }
  return outside.map { !$0 }
}

/// Exact squared distance transform of one line (Felzenszwalb and Huttenlocher).
func distanceTransform(_ f: [Double]) -> [Double] {
  let n = f.count
  var out = [Double](repeating: 0, count: n)
  var hull = [Int](repeating: 0, count: n)
  var bounds = [Double](repeating: 0, count: n + 1)
  var k = 0
  bounds[0] = -.infinity
  bounds[1] = .infinity
  func meet(_ q: Int, _ p: Int) -> Double {
    ((f[q] + Double(q * q)) - (f[p] + Double(p * p))) / Double(2 * q - 2 * p)
  }
  for q in 1..<n {
    var s = meet(q, hull[k])
    while s <= bounds[k] {
      k -= 1
      s = meet(q, hull[k])
    }
    k += 1
    hull[k] = q
    bounds[k] = s
    bounds[k + 1] = .infinity
  }
  k = 0
  for q in 0..<n {
    while bounds[k + 1] < Double(q) { k += 1 }
    let d = Double(q - hull[k])
    out[q] = d * d + f[hull[k]]
  }
  return out
}

/// Euclidean distance from each pixel to the nearest pixel set in `mask`.
func distance(to mask: [Bool], width: Int, height: Int) -> [Float] {
  var squared = mask.map { $0 ? 0 : 1e20 }
  for x in 0..<width {
    let column = distanceTransform((0..<height).map { squared[$0 * width + x] })
    for y in 0..<height { squared[y * width + x] = column[y] }
  }
  for y in 0..<height {
    let line = (y * width)..<(y * width + width)
    squared.replaceSubrange(line, with: distanceTransform(Array(squared[line])))
  }
  return squared.map { Float($0.squareRoot()) }
}

/// Mean over a (2r+1)² box, edges clamped.
func boxMean(_ plane: [Float], width: Int, height: Int, radius r: Int) -> [Float] {
  func pass(_ input: [Float], stride: Int, count: Int, lines: Int, lineStride: Int) -> [Float] {
    var output = input
    for line in 0..<lines {
      let base = line * lineStride
      for i in 0..<count {
        var sum: Float = 0
        for k in -r...r { sum += input[base + min(max(i + k, 0), count - 1) * stride] }
        output[base + i * stride] = sum / Float(2 * r + 1)
      }
    }
    return output
  }
  let rows = pass(plane, stride: 1, count: width, lines: height, lineStride: width)
  return pass(rows, stride: width, count: height, lines: width, lineStride: 1)
}

/// Labels 8-connected regions of pixels that share a class; -1 for pixels with no class.
func components(of classes: [Int], width: Int, height: Int) -> (labels: [Int], count: Int) {
  var labels = [Int](repeating: -1, count: classes.count)
  var count = 0
  for start in 0..<classes.count where classes[start] >= 0 && labels[start] < 0 {
    labels[start] = count
    var stack = [start]
    while let i = stack.popLast() {
      let x = i % width
      let y = i / width
      for ny in max(y - 1, 0)...min(y + 1, height - 1) {
        for nx in max(x - 1, 0)...min(x + 1, width - 1) {
          let j = ny * width + nx
          if labels[j] < 0 && classes[j] == classes[i] {
            labels[j] = count
            stack.append(j)
          }
        }
      }
    }
    count += 1
  }
  return (labels, count)
}

// MARK: Glyph

struct Glyph {
  let mask: [Bool]
  /// Distance from each pixel outside the glyph to the glyph.
  let outside: [Float]
  /// Distance from each pixel inside the glyph to its edge.
  let inside: [Float]
}

/// Everything near-black or clearly coloured, plus the mid-dark greys that carry the glyph's
/// blue tint (the lit bevel of the terminal, the hat's panels; b - r is about +4 there). The
/// shadow under the terminal is as dark as that bevel but neutral grey, like the background, so
/// it stays out. Then closed over the thin highlights along the edges (3 px), and filled.
func findGlyph(_ master: Picture, opaque: [Bool]) -> Glyph {
  let (width, height) = (master.width, master.height)
  let tint = boxMean(
    (0..<master.count).map { master.blue[$0] - master.red[$0] }, width: width, height: height,
    radius: 2)
  let core = (0..<master.count).map { i -> Bool in
    guard opaque[i] else { return false }
    let (r, g, b) = (master.red[i], master.green[i], master.blue[i])
    let lum = (r + g + b) / 3
    return lum < 50 || (lum < 110 && tint[i] >= 2) || max(r, g, b) - min(r, g, b) > 40
  }
  let grown = distance(to: core, width: width, height: height).map { $0 <= 3 }
  let closed = distance(to: grown.map { !$0 }, width: width, height: height).map { $0 > 3 }
  let mask = fillHoles(closed, width: width, height: height)
  return Glyph(
    mask: mask, outside: distance(to: mask, width: width, height: height),
    inside: distance(to: mask.map { !$0 }, width: width, height: height))
}

/// The smallest circle around every pixel of the mask, in pixel-edge coordinates.
func enclosingCircle(_ mask: [Bool], width: Int, height: Int) -> (x: Float, y: Float, r: Float) {
  var points: [(x: Float, y: Float)] = []
  for y in 0..<height {
    let row = (0..<width).filter { mask[y * width + $0] }
    guard let first = row.first, let last = row.last else { continue }
    for x in [Float(first), Float(last + 1)] {
      points.append((x, Float(y)))
      points.append((x, Float(y + 1)))
    }
  }
  guard !points.isEmpty else { fail("found no glyph") }
  func radius(_ cx: Float, _ cy: Float) -> Float {
    points.reduce(0) {
      max($0, (($1.x - cx) * ($1.x - cx) + ($1.y - cy) * ($1.y - cy)).squareRoot())
    }
  }
  var cx = points.reduce(0) { $0 + $1.x } / Float(points.count)
  var cy = points.reduce(0) { $0 + $1.y } / Float(points.count)
  var best = radius(cx, cy)
  var step: Float = 64
  while step > 0.01 {
    var moved = false
    for (dx, dy) in [(step, 0), (-step, 0), (0, step), (0, -step)] {
      let r = radius(cx + dx, cy + dy)
      if r < best {
        best = r
        cx += dx
        cy += dy
        moved = true
      }
    }
    if !moved { step /= 2 }
  }
  return (cx, cy, best)
}

// MARK: Background

/// A smooth field through the known pixels, filled into the gaps by pull-push over 8 px cells.
struct Field {
  let originX: Float
  let originY: Float
  let cell: Float
  let columns: Int
  let planes: [[Float]]

  func sample(_ channel: Int, x: Float, y: Float) -> Float {
    bilinear(
      planes[channel], width: columns, height: columns, x: (x - originX) / cell - 0.5,
      y: (y - originY) / cell - 0.5, outside: nil)
  }
}

struct Level {
  let size: Int
  var weight: [Float]
  var planes: [[Float]]

  init(size: Int) {
    self.size = size
    weight = .init(repeating: 0, count: size * size)
    planes = .init(repeating: .init(repeating: 0, count: size * size), count: 3)
  }

  /// Each cell the weighted mean of its four children; a weight of 1 means fully known.
  func coarser() -> Level {
    var coarse = Level(size: (size + 1) / 2)
    for cy in 0..<coarse.size {
      for cx in 0..<coarse.size {
        let c = cy * coarse.size + cx
        for fy in (2 * cy)..<min(2 * cy + 2, size) {
          for fx in (2 * cx)..<min(2 * cx + 2, size) {
            let f = fy * size + fx
            coarse.weight[c] += weight[f]
            for p in 0..<3 { coarse.planes[p][c] += weight[f] * planes[p][f] }
          }
        }
        if coarse.weight[c] > 0 { for p in 0..<3 { coarse.planes[p][c] /= coarse.weight[c] } }
        coarse.weight[c] = min(coarse.weight[c], 1)
      }
    }
    return coarse
  }

  /// The known part of each cell, with the rest taken from the filled coarser level.
  func filled(from coarse: [[Float]], size coarseSize: Int) -> [[Float]] {
    var out = planes
    for cy in 0..<size {
      for cx in 0..<size {
        let c = cy * size + cx
        for p in 0..<3 {
          let up = bilinear(
            coarse[p], width: coarseSize, height: coarseSize, x: (Float(cx) + 0.5) / 2 - 0.5,
            y: (Float(cy) + 0.5) / 2 - 0.5, outside: nil)
          out[p][c] = weight[c] * planes[p][c] + (1 - weight[c]) * up
        }
      }
    }
    return out
  }
}

func fitField(_ picture: Picture, known: [Bool], originX: Int, originY: Int, extent: Int) -> Field {
  let cell = 8
  var finest = Level(size: (extent + cell - 1) / cell)
  for i in 0..<picture.count where known[i] {
    let cx = (i % picture.width - originX) / cell
    let cy = (i / picture.width - originY) / cell
    guard cx >= 0, cy >= 0, cx < finest.size, cy < finest.size else { continue }
    let c = cy * finest.size + cx
    finest.weight[c] += 1
    for p in 0..<3 { finest.planes[p][c] += picture.channel(p)[i] }
  }
  for c in 0..<finest.weight.count where finest.weight[c] > 0 {
    for p in 0..<3 { finest.planes[p][c] /= finest.weight[c] }
    finest.weight[c] /= Float(cell * cell)
  }
  var levels = [finest]
  while levels.last!.size > 1 { levels.append(levels.last!.coarser()) }
  guard levels.last!.weight[0] > 0 else { fail("found no background pixels") }
  var filled = levels.last!.planes
  for (index, level) in levels.enumerated().dropLast().reversed() {
    filled = level.filled(from: filled, size: levels[index + 1].size)
  }
  return Field(
    originX: Float(originX), originY: Float(originY), cell: Float(cell), columns: finest.size,
    planes: filled)
}

struct Background {
  let field: Field
  /// The master minus the field, per channel.
  let difference: [[Float]]
  /// The same, averaged over 5 × 5 so the grain in the grey averages out.
  let local: [[Float]]
}

/// The grey, fitted twice: the second fit drops pixels the first shows to be shadow.
func fitBackground(
  _ master: Picture, opaque: [Bool], glyph: Glyph, centre: (x: Float, y: Float), reach: Float
) -> Background {
  let (width, height) = (master.width, master.height)
  let fromEdge = distance(to: opaque.map { !$0 }, width: width, height: height)
  let originX = Int((centre.x - reach).rounded(.down)) - 16
  let originY = Int((centre.y - reach).rounded(.down)) - 16
  let extent = Int((2 * reach).rounded(.up)) + 32
  var known = (0..<master.count).map { opaque[$0] && fromEdge[$0] > 6 && glyph.outside[$0] > 64 }
  func fit() -> Background {
    let field = fitField(
      master, known: known, originX: originX, originY: originY, extent: extent)
    var difference = [[Float]](repeating: .init(repeating: 0, count: master.count), count: 3)
    for i in 0..<master.count where opaque[i] {
      let x = Float(i % width) + 0.5
      let y = Float(i / width) + 0.5
      for p in 0..<3 { difference[p][i] = master.channel(p)[i] - field.sample(p, x: x, y: y) }
    }
    let local = difference.map { boxMean($0, width: width, height: height, radius: 2) }
    return Background(field: field, difference: difference, local: local)
  }
  let first = fit()
  for i in 0..<master.count where known[i] {
    if (0..<3).contains(where: { abs(first.local[$0][i]) > 6 }) { known[i] = false }
  }
  return fit()
}

// MARK: Foreground

/// The least alpha that rebuilds each pixel over the background, opaque inside the glyph. Grain
/// in the grey (a few levels) stays behind; the glyph's shadow comes along. Premultiplied RGBA.
func splitForeground(
  _ master: Picture, opaque: [Bool], glyph: Glyph, background: Background
) -> [[Float]] {
  var foreground = [[Float]](repeating: .init(repeating: 0, count: master.count), count: 4)
  for i in 0..<master.count where opaque[i] {
    let near = min(max((96 - glyph.outside[i]) / 24, 0), 1)
    let differs = smoothstep(2.5, 7, (0..<3).map { abs(background.local[$0][i]) }.max()!)
    let keep: Float = glyph.mask[i] ? 1 : near * differs
    guard keep > 0 else { continue }
    let x = Float(i % master.width) + 0.5
    let y = Float(i / master.width) + 0.5
    let grey = (0..<3).map { background.field.sample($0, x: x, y: y) }
    let change = (0..<3).map { keep * background.difference[$0][i] }
    var alpha: Float = glyph.mask[i] ? min(max((glyph.inside[i] - 1) / 2, 0), 1) : 0
    for p in 0..<3 {
      if change[p] < 0 { alpha = max(alpha, -change[p] / max(grey[p], 1)) }
      if change[p] > 0 { alpha = max(alpha, change[p] / max(255 - grey[p], 1)) }
    }
    guard alpha > 1.0 / 512 else { continue }
    alpha = min(alpha, 1)
    for p in 0..<3 {
      let colour = min(max(grey[p] + change[p] / alpha, 0), 255)
      foreground[p][i] = alpha * colour / 255
    }
    foreground[3][i] = alpha
  }
  return foreground
}

// MARK: Monochrome

/// The glyph's silhouette, in two colours that become ink and gaps. The glyph is green parts
/// (the robot, the prompt) and dark parts (the hat, the terminal, the eyes). A part that reaches
/// the outline is ink; a part enclosed by the other colour (the eyes in the head, the prompt in
/// the terminal) is cut out. Where the robot passes behind the hat or the terminal, the robot
/// gives up a thin gap so the shapes stay apart.
func monochromeInk(_ master: Picture, glyph: Glyph) -> [Float] {
  let (width, height) = (master.width, master.height)
  let gapBehindHat: Float = 10
  let gapBehindTerminal: Float = 6
  let classes = (0..<master.count).map { i -> Int in
    guard glyph.mask[i] else { return -1 }
    let (r, g, b) = (master.red[i], master.green[i], master.blue[i])
    return g - max(r, b) > 20 && max(r, g, b) - min(r, g, b) > 30 ? 0 : 1
  }
  let (labels, labelCount) = components(of: classes, width: width, height: height)
  var reachesOutline = [Bool](repeating: false, count: labelCount)
  var area = [Int](repeating: 0, count: labelCount)
  var labelClass = [Int](repeating: -1, count: labelCount)
  for i in 0..<master.count where labels[i] >= 0 {
    area[labels[i]] += 1
    labelClass[labels[i]] = classes[i]
    let x = i % width
    let neighbours = [
      x > 0 ? i - 1 : i, x < width - 1 ? i + 1 : i, max(i - width, 0),
      min(i + width, master.count - 1),
    ]
    if neighbours.contains(where: { !glyph.mask[$0] }) { reachesOutline[labels[i]] = true }
  }
  // The two largest dark shapes on the outline are the terminal and the hat; the rest are specks.
  let fronts = (0..<labelCount).filter { labelClass[$0] == 1 && reachesOutline[$0] }
    .sorted { area[$0] > area[$1] }
  guard fronts.count >= 2, area[fronts[1]] > master.count / 50 else {
    fail("expected the hat and the terminal as separate dark shapes")
  }
  let fromTerminal = distance(to: labels.map { $0 == fronts[0] }, width: width, height: height)
  let fromHat = distance(to: labels.map { $0 == fronts[1] }, width: width, height: height)
  return (0..<master.count).map { i -> Float in
    guard labels[i] >= 0, reachesOutline[labels[i]] || area[labels[i]] < 40 else { return 0 }
    let behind = classes[i] == 0 && reachesOutline[labels[i]]
    let inGap = fromHat[i] < gapBehindHat || fromTerminal[i] < gapBehindTerminal
    return behind && inGap ? 0 : 1
  }
}

// MARK: Main

let arguments = CommandLine.arguments
guard arguments.count == 3 else {
  fail("usage: swift -O launcher-icon-layers.swift <master.png> <out-dir>")
}
let master = load(URL(fileURLWithPath: arguments[1]))
let outDir = URL(fileURLWithPath: arguments[2], isDirectory: true)
let opaque = master.alpha.map { $0 >= 0.999 }
let glyph = findGlyph(master, opaque: opaque)
let circle = enclosingCircle(glyph.mask, width: master.width, height: master.height)
let scale = safeZoneRadiusDp * pxPerDp / circle.r
let halfCanvas = Float(canvasSize) / 2
let background = fitBackground(
  master, opaque: opaque, glyph: glyph, centre: (circle.x, circle.y), reach: halfCanvas / scale)
let foreground = splitForeground(master, opaque: opaque, glyph: glyph, background: background)
let ink = monochromeInk(master, glyph: glyph)

// Each canvas pixel, back to the master.
var layers = [[Float]](repeating: .init(repeating: 0, count: canvasSize * canvasSize * 4), count: 3)
for v in 0..<canvasSize {
  for u in 0..<canvasSize {
    let x = circle.x + (Float(u) + 0.5 - halfCanvas) / scale
    let y = circle.y + (Float(v) + 0.5 - halfCanvas) / scale
    let o = (v * canvasSize + u) * 4
    for p in 0..<3 { layers[0][o + p] = background.field.sample(p, x: x, y: y) / 255 }
    for p in 0..<4 {
      layers[1][o + p] = bilinear(
        foreground[p], width: master.width, height: master.height, x: x - 0.5, y: y - 0.5,
        outside: 0)
    }
    let m = bilinear(
      ink, width: master.width, height: master.height, x: x - 0.5, y: y - 0.5, outside: 0)
    for p in 0..<4 { layers[2][o + p] = m }
  }
}

do {
  try FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)
} catch {
  fail("can't create \(outDir.path): \(error.localizedDescription)")
}
for (index, name) in ["background", "foreground", "monochrome"].enumerated() {
  write(
    layers[index], size: canvasSize, opaque: index == 0,
    to: outDir.appendingPathComponent("\(name).png"))
}
print(
  String(
    format: "glyph circle: centre (%.1f, %.1f), radius %.1f px of %d; scale %.4f",
    circle.x, circle.y, circle.r, master.width, scale))
