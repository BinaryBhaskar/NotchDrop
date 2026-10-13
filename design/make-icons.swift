// Renders the NotchDrop icon for macOS (.icns) and Android (adaptive icon layers).
// Usage (from the repo root): swift design/make-icons.swift
import AppKit
import CoreGraphics

let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
let space = CGColorSpace(name: CGColorSpace.displayP3)!

func color(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
    CGColor(colorSpace: space, components: [CGFloat((hex >> 16) & 0xFF) / 255, CGFloat((hex >> 8) & 0xFF) / 255, CGFloat(hex & 0xFF) / 255, a])!
}

func render(_ size: Int, _ draw: (CGContext, CGFloat) -> Void) -> Data {
    let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.interpolationQuality = .high
    draw(ctx, CGFloat(size))
    let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
    return rep.representation(using: .png, properties: [:])!
}

/// Apple-style continuous-corner squircle (superellipse) in rect r.
func squircle(_ r: CGRect, n: CGFloat = 5) -> CGPath {
    let p = CGMutablePath()
    let steps = 720
    for i in 0...steps {
        let t = CGFloat(i) / CGFloat(steps) * 2 * .pi
        let c = cos(t), s = sin(t)
        let x = pow(abs(c), 2 / n) * (c < 0 ? -1 : 1)
        let y = pow(abs(s), 2 / n) * (s < 0 ? -1 : 1)
        let pt = CGPoint(x: r.midX + x * r.width / 2, y: r.midY + y * r.height / 2)
        i == 0 ? p.move(to: pt) : p.addLine(to: pt)
    }
    p.closeSubpath()
    return p
}

/// Background: blue→violet gradient with a soft top-left glow.
func drawBackground(_ c: CGContext, _ r: CGRect) {
    let g = CGGradient(colorsSpace: space, colors: [color(0x67A6FF), color(0x5B6CFF), color(0x8A45F2)] as CFArray, locations: [0, 0.5, 1])!
    c.drawLinearGradient(g, start: CGPoint(x: r.minX, y: r.maxY), end: CGPoint(x: r.maxX * 0.7, y: r.minY), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    let glow = CGGradient(colorsSpace: space, colors: [color(0xFFFFFF, 0.35), color(0xFFFFFF, 0)] as CFArray, locations: [0, 1])!
    c.drawRadialGradient(glow, startCenter: CGPoint(x: r.minX + r.width * 0.25, y: r.maxY - r.height * 0.15), startRadius: 0,
                         endCenter: CGPoint(x: r.minX + r.width * 0.25, y: r.maxY - r.height * 0.15), endRadius: r.width * 0.75, options: [])
}

/// Foreground in unit space `u` (a square): frosted drop tray, notch pill, glass arrow.
func drawGlyph(_ c: CGContext, _ u: CGRect, notchTouchesTop: Bool) {
    func R(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> CGRect {
        CGRect(x: u.minX + x * u.width, y: u.minY + y * u.height, width: w * u.width, height: h * u.height)
    }
    let s = u.width

    // Frosted glass tray (the drop zone).
    let tray = R(0.17, 0.14, 0.66, 0.42)
    let trayPath = CGPath(roundedRect: tray, cornerWidth: s * 0.11, cornerHeight: s * 0.11, transform: nil)
    c.saveGState()
    c.setShadow(offset: CGSize(width: 0, height: -s * 0.02), blur: s * 0.06, color: color(0x1A0B5C, 0.35))
    c.addPath(trayPath); c.setFillColor(color(0xFFFFFF, 0.16)); c.fillPath()
    c.restoreGState()
    c.saveGState()
    c.addPath(trayPath); c.clip()
    let sheen = CGGradient(colorsSpace: space, colors: [color(0xFFFFFF, 0.30), color(0xFFFFFF, 0.04)] as CFArray, locations: [0, 1])!
    c.drawLinearGradient(sheen, start: CGPoint(x: tray.midX, y: tray.maxY), end: CGPoint(x: tray.midX, y: tray.minY), options: [])
    c.restoreGState()
    // Dashed outline, like the Mac drop box.
    let inset = tray.insetBy(dx: s * 0.035, dy: s * 0.035)
    c.saveGState()
    c.addPath(CGPath(roundedRect: inset, cornerWidth: s * 0.08, cornerHeight: s * 0.08, transform: nil))
    c.setStrokeColor(color(0xFFFFFF, 0.85)); c.setLineWidth(s * 0.018); c.setLineCap(.round)
    c.setLineDash(phase: 0, lengths: [s * 0.045, s * 0.035])
    c.strokePath()
    c.restoreGState()
    // Thin glass rim.
    c.addPath(trayPath); c.setStrokeColor(color(0xFFFFFF, 0.45)); c.setLineWidth(s * 0.006); c.strokePath()

    // Notch: black pill with flared top corners when attached to the top edge.
    let nw = s * 0.40, nh = s * 0.14, rr = s * 0.05
    let top = notchTouchesTop ? u.maxY : u.minY + u.height * 0.86
    let nx = u.midX - nw / 2
    let notch = CGMutablePath()
    if notchTouchesTop {
        let f = s * 0.03
        notch.move(to: CGPoint(x: nx - f, y: top + 2))
        notch.addQuadCurve(to: CGPoint(x: nx, y: top - f), control: CGPoint(x: nx, y: top))
        notch.addLine(to: CGPoint(x: nx, y: top - nh + rr))
        notch.addQuadCurve(to: CGPoint(x: nx + rr, y: top - nh), control: CGPoint(x: nx, y: top - nh))
        notch.addLine(to: CGPoint(x: nx + nw - rr, y: top - nh))
        notch.addQuadCurve(to: CGPoint(x: nx + nw, y: top - nh + rr), control: CGPoint(x: nx + nw, y: top - nh))
        notch.addLine(to: CGPoint(x: nx + nw, y: top - f))
        notch.addQuadCurve(to: CGPoint(x: nx + nw + f, y: top + 2), control: CGPoint(x: nx + nw, y: top))
        notch.closeSubpath()
    } else {
        notch.addPath(CGPath(roundedRect: CGRect(x: nx, y: top - nh, width: nw, height: nh), cornerWidth: nh / 2, cornerHeight: nh / 2, transform: nil))
    }
    c.saveGState()
    c.setShadow(offset: CGSize(width: 0, height: -s * 0.012), blur: s * 0.04, color: color(0x000000, 0.35))
    c.addPath(notch); c.setFillColor(color(0x0A0B10)); c.fillPath()
    c.restoreGState()
    // Camera dot.
    let cam = s * 0.022
    c.setFillColor(color(0x1E2433)); c.fillEllipse(in: CGRect(x: u.midX - cam, y: top - nh / 2 - cam, width: cam * 2, height: cam * 2))
    c.setFillColor(color(0x3E5AA8, 0.9)); c.fillEllipse(in: CGRect(x: u.midX - cam * 0.45, y: top - nh / 2 - cam * 0.45 + cam * 0.2, width: cam * 0.9, height: cam * 0.9))

    // Glass arrow from the notch down into the tray.
    let ax = u.midX
    let shaftW = s * 0.085, headW = s * 0.25, headH = s * 0.15
    let yTop = top - nh - s * 0.03, yTip = tray.minY + tray.height * 0.30
    let arrow = CGMutablePath()
    arrow.move(to: CGPoint(x: ax - shaftW / 2, y: yTop))
    arrow.addLine(to: CGPoint(x: ax - shaftW / 2, y: yTip + headH))
    arrow.addLine(to: CGPoint(x: ax - headW / 2, y: yTip + headH))
    arrow.addLine(to: CGPoint(x: ax, y: yTip))
    arrow.addLine(to: CGPoint(x: ax + headW / 2, y: yTip + headH))
    arrow.addLine(to: CGPoint(x: ax + shaftW / 2, y: yTip + headH))
    arrow.addLine(to: CGPoint(x: ax + shaftW / 2, y: yTop))
    arrow.closeSubpath()
    // Fill + round-joined stroke in one transparency layer, then tint with a gradient (sourceAtop).
    c.saveGState()
    c.setShadow(offset: CGSize(width: 0, height: -s * 0.015), blur: s * 0.05, color: color(0x24105E, 0.45))
    c.beginTransparencyLayer(auxiliaryInfo: nil)
    c.setFillColor(color(0xFFFFFF)); c.setStrokeColor(color(0xFFFFFF))
    c.setLineWidth(s * 0.03); c.setLineJoin(.round); c.setLineCap(.round)
    c.addPath(arrow); c.drawPath(using: .fillStroke)
    c.setBlendMode(.sourceAtop)
    let ag = CGGradient(colorsSpace: space, colors: [color(0xFFFFFF), color(0xE4DCFF)] as CFArray, locations: [0, 1])!
    c.drawLinearGradient(ag, start: CGPoint(x: ax, y: yTop), end: CGPoint(x: ax, y: yTip), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    c.endTransparencyLayer()
    c.restoreGState()
}

// MARK: macOS

func macIcon(_ px: Int) -> Data {
    render(px) { c, S in
        // Standard macOS grid: 824pt artwork inside a 1024pt canvas.
        let r = CGRect(x: S * 100 / 1024, y: S * 100 / 1024, width: S * 824 / 1024, height: S * 824 / 1024)
        let shape = squircle(r)
        c.saveGState()
        c.setShadow(offset: CGSize(width: 0, height: -S * 0.012), blur: S * 0.03, color: color(0x000000, 0.3))
        c.addPath(shape); c.setFillColor(color(0x5B6CFF)); c.fillPath()
        c.restoreGState()
        c.saveGState()
        c.addPath(shape); c.clip()
        drawBackground(c, r)
        drawGlyph(c, r, notchTouchesTop: true)
        c.restoreGState()
        // Liquid-glass rim light.
        c.saveGState()
        c.addPath(shape); c.clip()
        c.addPath(shape); c.setStrokeColor(color(0xFFFFFF, 0.35)); c.setLineWidth(S * 0.006); c.strokePath()
        c.restoreGState()
    }
}

let iconset = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("AppIcon.iconset")
try? FileManager.default.removeItem(at: iconset)
try! FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
for base in [16, 32, 128, 256, 512] {
    try! macIcon(base).write(to: iconset.appendingPathComponent("icon_\(base)x\(base).png"))
    try! macIcon(base * 2).write(to: iconset.appendingPathComponent("icon_\(base)x\(base)@2x.png"))
}
let resources = root.appendingPathComponent("mac/Resources")
try! FileManager.default.createDirectory(at: resources, withIntermediateDirectories: true)
let p = Process()
p.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
p.arguments = ["-c", "icns", iconset.path, "-o", resources.appendingPathComponent("AppIcon.icns").path]
try! p.run(); p.waitUntilExit()
try! macIcon(1024).write(to: root.appendingPathComponent("design/icon-mac.png"))

// MARK: Android adaptive icon (108dp layers; the glyph stays inside the 66dp safe zone)

let densities: [(String, Int)] = [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]
let res = root.appendingPathComponent("android/app/src/main/res")
for (d, px) in densities {
    let dir = res.appendingPathComponent("mipmap-\(d)")
    try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    try! render(px) { c, S in drawBackground(c, CGRect(x: 0, y: 0, width: S, height: S)) }
        .write(to: dir.appendingPathComponent("ic_launcher_background.png"))
    try! render(px) { c, S in
        let safe = S * 64 / 108
        drawGlyph(c, CGRect(x: (S - safe) / 2, y: (S - safe) / 2, width: safe, height: safe), notchTouchesTop: false)
    }.write(to: dir.appendingPathComponent("ic_launcher_foreground.png"))
}
// Flattened preview of the Android icon (circle mask) for the README.
try! render(512) { c, S in
    let r = CGRect(x: 0, y: 0, width: S, height: S)
    c.addEllipse(in: r); c.clip()
    let full = CGRect(x: -S / 6, y: -S / 6, width: S * 4 / 3, height: S * 4 / 3) // 108dp layer, 72dp visible
    drawBackground(c, full)
    let safe = full.width * 64 / 108
    drawGlyph(c, CGRect(x: full.midX - safe / 2, y: full.midY - safe / 2, width: safe, height: safe), notchTouchesTop: false)
}.write(to: root.appendingPathComponent("design/icon-android.png"))
print("icons written")
