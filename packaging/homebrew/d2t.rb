class D2t < Formula
  desc "Diff-driven Android ViewModel test generation CLI"
  homepage "https://github.com/gay00ung/diff2test-android"
  url "https://github.com/gay00ung/diff2test-android/releases/download/v0.1.0/d2t.zip"
  sha256 "98f798ddb585dab4c7fff926a8f48154c5928dd9bc83277c926b3d263b28a214"
  version "0.1.0"

  depends_on "openjdk@17"

  def install
    libexec.install Dir["*"]
    bin.install_symlink libexec/"bin/d2t"
  end

  test do
    output = shell_output("#{bin}/d2t help")
    assert_match "d2t commands:", output
  end
end
