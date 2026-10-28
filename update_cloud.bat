@echo off
echo ==================================================
echo   NeoRadio: Cloud Sync and Mobile Update
echo ==================================================
echo.
echo [1/2] Syncing local files to mobile_web...
copy /Y stations.json mobile_web\stations.json > nul
copy /Y index.html mobile_web\index.html > nul
copy /Y sw.js mobile_web\sw.js > nul
copy /Y manifest.json mobile_web\manifest.json > nul
echo Done.
echo.
echo [2/2] Publishing to Cloud CDN (ShipStatic)...
call npx.cmd -y @shipstatic/ship deployments upload mobile_web --domain neoradio.shipstatic.com --token ship-ff341cd69916de898a345a667a187380
echo.
echo ==================================================
echo   Successfully deployed to https://neoradio.shipstatic.com
echo   Mobile devices will auto-update on launch.
echo ==================================================
pause
