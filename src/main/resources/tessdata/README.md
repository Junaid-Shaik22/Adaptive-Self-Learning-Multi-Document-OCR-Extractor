## Tessdata Directory

Place your Tesseract language data files here.

### Required file:
- `eng.traineddata` — English language model (≈ 12 MB)

### Download (choose one):

**Option A (Tesseract 4/5 LSTM — RECOMMENDED for best accuracy):**
```
https://github.com/tesseract-ocr/tessdata_best/raw/main/eng.traineddata
```

**Option B (fast model, smaller):**
```
https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata
```

### Direct PowerShell download command:
```powershell
Invoke-WebRequest -Uri "https://github.com/tesseract-ocr/tessdata_best/raw/main/eng.traineddata" `
  -OutFile "src\main\resources\tessdata\eng.traineddata"
```

After downloading, this folder should contain:
```
tessdata/
└── eng.traineddata   (~12 MB)
```
