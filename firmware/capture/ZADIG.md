# Установка драйвера WinUSB для нативного USB ESP32-S3 (Zadig)

На Windows нативный USB ESP32-S3 (VID_303A:PID_1001) определяется как
"USB JTAG/serial debug unit" и не открывается через pyserial из-за стандартного
драйвера `usbser`. Для стабильной работы с прошивкой capture через нативный
USB нужно переустановить драйвер на WinUSB или libusb.

## Предварительные требования

- Zadig 2.7+ (https://zadig.akeo.ie/)
- Прошивка capture, использующая `usb_serial_jtag_*` (не UART)
- Права администратора

## Шаги

1. Подключите ESP32-S3 к ПК через нативный USB (DevKitC-1: USB-порт рядом с
   JTAG-разъёмом, не CH9102 UART).
2. Запустите Zadig от имени администратора.
3. В меню `Options` → `List All Devices`.
4. В выпадающем списке найдите устройство:
   - `USB JTAG/serial debug unit`
   - или `ESP32-S3` / `Unknown device`
   - VID: `303A`, PID: `1001`
5. В поле `Driver` выберите `WinUSB (v6.x)` или `libusb-win32 (v3.x)`.
6. Нажмите `Replace Driver`.
7. После установки в Device Manager устройство должно появиться как
   `WinUSB Device` или `libusb-win32 device`.

## Проверка

```powershell
# Должен появиться новый COM-порт или устройство WinUSB
Get-PnpDevice -Class Ports | Where-Object { $_.InstanceId -like "*303A*" }
```

## Откат

Если нужно вернуть стандартный драйвер:
1. Zadig → `Options` → `List All Devices`
2. Выберите то же устройство
3. В `Driver` выберите `usbser` или `USB Serial Device`
4. `Replace Driver`

## Альтернатива

Если Zadig не помогает или нативный USB продолжает исчезать — используйте
UART (COM11) через CH9102. Прошивка capture переключена на UART в ветке
`agent2-codefix`, это надёжнее на Windows.
