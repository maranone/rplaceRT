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
--> This went slower than expected
--> Need to create a "out" folder first
--> You need this library: http://www.massmind.org/techref/language/asp/vbs/vbscript/imgClass.htm
Dim w
Set w = New ImgClass
w.Width=4000/100
w.Height=4000/100
'w.Depth=24

Dim i,j,x,h,hh
dim filename, fso,f,g
filename = "2022_place_canvas_history.csv"
Set fso = CreateObject("Scripting.FileSystemObject")
Set f = fso.OpenTextFile(filename)
x=0
dim hexcol
dim ini_hour,ini_minute,ini_second
dim ini_time
dim aqDateTime,aDateTime
dim time_difference,last_write,last_folder
ini_time = ""
last_write=0
last_folder=1
dim filesys,newfolder
set filesys=CreateObject("Scripting.FileSystemObject")
If Not filesys.FolderExists(last_folder) Then
    Set newfolder = filesys.CreateFolder(last_folder)
end if
If Not filesys.FolderExists("sort") Then
    Set newfolder = filesys.CreateFolder("sort")
end if
Do Until f.AtEndOfStream
  if x<1 then 
    f.readline
    x=x+1
  else
  x=x+1
  g=split(f.ReadLine,",")
  h=split(g(0)," ")
  hh=split(h(1),".")
  if ini_time="" then
    ini_time=formatdatetime(hh(0))
  end if
  if DateDiff("s", ini_time,formatdatetime(hh(0))) > last_write then
    last_write=last_write+1
    if last_write > 3220 then msgbox last_write & g(0) & " " & g(1) & " " & g(2) & " " & g(3) & " " & g(4)
    w.SavePCX(last_folder & "\img" & Right(String(8,"0") & last_write, 8) & ".pcx")
  end if
  if DateDiff("h", ini_time,formatdatetime(hh(0))) > last_folder then
    last_folder=last_folder+1
    ini_time = formatdatetime(hh(0))
    last_write = 0
    if last_folder > 10 then wscript.quit
    If Not filesys.FolderExists(last_folder) Then
    Set newfolder = filesys.CreateFolder(last_folder)
    end if
  end if
  i = round((replace(g(3),chr(34),"")/101))
  j = round((replace(g(4),chr(34),"")/101))
  
  if i=0 or i="" or j=0 or j="" then
  else
  hexcol= split(hexcol2rgb(g(2)),",")
  w.quickpixel(i,j)=(hexcol(0)*6/256)*36 + (hexcol(1)*6/256)*6 + (hexcol(2)*6/256)
  end if
end if
Loop
f.Close
'w.SaveBMP("red_on_black.bmp")
'x.Display
w.DisplayInfo
Set w = Nothing


Public Function hexcol2rgb(sParam)
    dim color,r,g,b
    Color = Replace(sParam, "#", "")
    R = CInt("&H" & Mid(Color,1,2))
    G = CInt("&H" & Mid(Color,3,2))
    B = CInt("&H" & Mid(Color,5,2))
    hexcol2rgb = R & "," & G & "," & B
end function
--> Step 3, FFMPEG to join those images 
--> This example used by a .bat (Batch) file contains double %% use single when cmd
path c:\ffmpeg\bin
cd out
ffmpeg -y -framerate 60 -r 1 -i img%%08d.png -pix_fmt yuv420p -movflags faststart -c:v hevc_nvenc c:\rplace\RT.mov

Thanks to this post for canvas information:
https://www.reddit.com/r/place/comments/txvk2d/rplace_datasets_april_fools_2022/
