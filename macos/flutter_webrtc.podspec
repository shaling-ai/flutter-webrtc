#
# Modified by Shaling AI in 2026: WebRTC-SDK 144.7559.10 (see SHALING_PATCHES.md).
# Not part of upstream flutter-webrtc/flutter-webrtc.
#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html
#
Pod::Spec.new do |s|
  s.name             = 'flutter_webrtc'
  s.version          = '1.4.0'
  s.summary          = 'Flutter WebRTC plugin for macOS.'
  s.description      = <<-DESC
A new flutter plugin project.
                       DESC
  s.homepage         = 'https://github.com/cloudwebrtc/flutter-webrtc'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'CloudWebRTC' => 'duanweiwei1982@gmail.com' }
  s.source           = { :path => '.' }
  s.source_files     = ['Classes/**/*']

  s.dependency 'FlutterMacOS'
  s.weak_frameworks = 'ScreenCaptureKit'
  # Shaling AI fork patch W1: 144.7559.10 fixes the AudioEngineDevice null
  # FineAudioBuffer crash of .09 (see SHALING_PATCHES.md). Public headers are
  # identical to .09.
  s.dependency 'WebRTC-SDK', '144.7559.10'
  s.osx.deployment_target = '10.15'
end
