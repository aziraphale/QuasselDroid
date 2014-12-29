@echo off

rem Just call the bash script :)
bash.exe build.sh

rem Show a "press any key to continue" message so that the output doesn't immediately disappear
echo.
echo Done! Check the above output for errors and then
echo press any key to continue!
pause > NUL
