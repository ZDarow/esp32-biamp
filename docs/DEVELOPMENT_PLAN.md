# План развития ESP32 BiAmp — новая ветка

## Этап 0. Подготовка и фиксация базовой линии
- [x] Исправить критичные находки аудита
- [x] git init + тег v35.2-local-base
- [x] Снять метрики: stats, click, Heap final, dsp_max_us, wr_max_us, loop_max_ms
- Бюджет DSP: 256 кадров @44.1 кГц = 5.8 мс, dsp_max_us <= 5000 мкс

## Этап 1. Качество тракта и управление
- [x] 1.1 Логарифмическая громкость + плавный шаг энкодера
- [x] 1.2 Треугольный дитеринг перед (int16_t)
- [x] 1.3 Внутренние RMS/пик-метры на канал
- [x] OLED: VOL XX% 1.5с, затем общий экран (BT, PLAY/PAUSE, VOL, XO, sub)

## Этап 2. Лимитер / компрессор
- [x] Добавить в AudioParams: lim_thr, lim_ratio, lim_att, lim_rel, lim_on
- [x] Состояние: lim_env[4] в audioTask
- [x] Интеграция в processBlock после applyDelay, перед softClip
- [x] Команды: limon:, limthr:, limratio:, limatt:, limrel:
- [x] NVS: PBlob v24 + миграция v23->v24

## Этап 3. Параметрический EQ и Loudness
- [x] Расширить calcSectionTo типами 6=peaking, 7=low_shelf, 8=high_shelf
- [ ] Loudness: зависимость tlf/thf от ap.vol[z]
- [x] Команды: peq:C:F:Q:DB
- [ ] NVS: v25 (peq_f, peq_q, peq_db_val)

## Этап 4. Продвинутые блоки
- [ ] Фазовая коррекция (All-pass)
- [ ] Улучшенный ресемплер (полифазный sinc)
- [ ] OLED-метры/спектр
- [ ] Автокалибровка

## Общие правила
1. Все новые параметры -> AudioParams -> scheduleFilterUpdate() -> ap
2. NVS: каждое изменение PBlob = +1 версия + миграция + sanitizeParams()
3. Производительность: dsp_max_us <= 5000 мкс
4. Регресс-тест: ringSelfTest, click, stats, 10 мин воспроизведения
5. Коммиты: один этап = одна ветка
