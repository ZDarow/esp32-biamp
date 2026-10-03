cmake_minimum_required(VERSION 3.22)

# Три строки порядка из ESP-IDF обязательны: без include из project.cmake
# cmake не подключает toolchain-файл ESP32 и берёт компилятор из PATH —
# проект собирается чем угодно, хоть gcc из MinGW.
include($ENV{IDF_PATH}/tools/cmake/project.cmake)

# Сниффер выхода Master (ESP32 BiAmp) на ESP32-S3. К прошивке BiAmp отношения
# не имеет: Master здесь только источник сигнала и его исходники не меняются.
# Каталог лежит в firmware/** ради владения путями — см. .github/CODEOWNERS.
project(esp32_biamp_capture)