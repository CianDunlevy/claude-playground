"""Builds the single-file artifact version of app/index.html (strips the document shell)."""
import pathlib, re, sys
src = pathlib.Path(__file__).resolve().parent.parent / "app" / "index.html"
html = src.read_text()
head = re.search(r"<!-- APP HEAD START -->(.*?)<!-- APP HEAD END -->", html, re.S).group(1)
body = re.search(r"<!-- APP BODY START -->(.*?)<!-- APP BODY END -->", html, re.S).group(1)
out = pathlib.Path(sys.argv[1])
out.write_text(head.strip() + "\n" + body.strip() + "\n")
print("wrote", out)
