import AppKit

let resourceRoot = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
  .appendingPathComponent("app/src/main/res")
let green = NSColor(srgbRed: 98 / 255, green: 215 / 255, blue: 164 / 255, alpha: 1)
let charcoal = NSColor(srgbRed: 16 / 255, green: 19 / 255, blue: 23 / 255, alpha: 1)

func drawText(_ text: String, in rect: NSRect, size: CGFloat, color: NSColor) {
  let attributes: [NSAttributedString.Key: Any] = [
    .font: NSFont.systemFont(ofSize: size, weight: .semibold),
    .foregroundColor: color,
  ]
  let value = text as NSString
  let measured = value.size(withAttributes: attributes)
  value.draw(at: NSPoint(x: rect.midX - measured.width / 2, y: rect.midY - measured.height / 2), withAttributes: attributes)
}

func render(width: Int, height: Int, path: String, draw: (NSRect) -> Void) throws {
  // long: 显式指定像素缓冲区，保证 1080p 电视横幅不随制作电脑的 Retina 缩放而变成双倍尺寸。
  let bitmap = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
  )!
  NSGraphicsContext.saveGraphicsState()
  NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
  draw(NSRect(x: 0, y: 0, width: width, height: height))
  NSGraphicsContext.restoreGraphicsState()
  let destination = resourceRoot.appendingPathComponent(path)
  try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
  try bitmap.representation(using: .png, properties: [:])!.write(to: destination, options: .atomic)
  print(destination.path)
}

try render(width: 320, height: 180, path: "drawable-xhdpi/tv_banner.png") { bounds in
  charcoal.setFill()
  bounds.fill()
  drawText("TV", in: NSRect(x: 25, y: 48, width: 108, height: 84), size: 58, color: green)
  drawText("直播", in: NSRect(x: 148, y: 48, width: 136, height: 84), size: 45, color: .white)
}

try render(width: 192, height: 192, path: "mipmap-xxxhdpi/ic_launcher.png") { bounds in
  green.setFill()
  bounds.fill()
  drawText("TV", in: bounds, size: 98, color: charcoal)
}
