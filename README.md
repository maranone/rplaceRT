# rplaceRT
Real Time r/place
--> Step 1, sort the canvas file into 1 file every second (VBScript)
--> Expect this to run forever (I used RAMDISK to r/w faster)
--> Need to create a "sort" folder first
x=0
filename = "2022_place_canvas_history.csv"
Set fso = CreateObject("Scripting.FileSystemObject")
Set f = fso.OpenTextFile(filename)
Do Until f.AtEndOfStream
if x<1 then 
    f.readline
    x=x+1
else
    g=split(f.ReadLine,",")
    h=split(g(0)," ")
    hh=split(h(1),".")
    Set objFileToWrite = CreateObject("Scripting.FileSystemObject").OpenTextFile("sort\" & h(0) & replace(hh(0),":","") & ".txt",8,true)
    objFileToWrite.WriteLine(g(2) & "," & replace(g(3),chr(34),"") & "," & replace(g(4),chr(34),""))
    objFileToWrite.Close
end if
loop
f.Close
--> Step 2, generate pictures from sorted files (VB.net)
--> This went faster than expected
--> Need to create a "out" folder first
Dim b As Bitmap = New Bitmap(2000, 2000)
Dim x
Dim files() As String = IO.Directory.GetFiles("sort")
x = 0
For Each file As String In files
    x += 1
    Dim fileReader As System.IO.StreamReader
    fileReader = My.Computer.FileSystem.OpenTextFileReader(file)
    Dim stringReader
    Do While fileReader.Peek() <> -1
       stringReader = Split(fileReader.ReadLine(), ",")
       b.SetPixel(stringReader(1), stringReader(2), ColorTranslator.FromHtml(stringReader(0)))
    Loop
    fileReader.Close()
    b.Save("out\img" & x.ToString().PadLeft(8, "0") & ".png", System.Drawing.Imaging.ImageFormat.Png)
Next
--> Step 3, FFMPEG to join those images 
--> This example used by a .bat (Batch) file contains double %% use single when cmd
path c:\ffmpeg\bin
cd out
ffmpeg -y -framerate 60 -r 1 -i img%%08d.png -pix_fmt yuv420p -movflags faststart -c:v hevc_nvenc c:\rplace\RT.mov

Thanks to this post for canvas information:
https://www.reddit.com/r/place/comments/txvk2d/rplace_datasets_april_fools_2022/
