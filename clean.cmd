@echo off

taskkill /f /t /im java.exe 2>nul

rmdir /s /q "build"
rmdir /s /q "trantor-ai\build"
rmdir /s /q "trantor-aws\build"
rmdir /s /q "trantor-bom\build"
rmdir /s /q "trantor-config\build"
rmdir /s /q "trantor-core\build"
rmdir /s /q "trantor-data\build"
rmdir /s /q "trantor-di\build"
rmdir /s /q "trantor-domain\build"
rmdir /s /q "trantor-gson\build"
rmdir /s /q "trantor-hosting\build"
rmdir /s /q "trantor-primitives\build"
rmdir /s /q "trantor-queues-sqs\build"
rmdir /s /q "trantor-taskpool\build"
rmdir /s /q "trantor-test\build"
rmdir /s /q "trantor-web\build"
rmdir /s /q "trantor-web-client\build"
