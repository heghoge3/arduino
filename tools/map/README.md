# Офлайн-карта

Карта в приложении работает без интернета: файл региона `.pmtiles` (выжимка Protomaps из OpenStreetMap)
загружается в «Настройки → Офлайн-карта». Стиль и шрифты лежат в APK.

## Файл региона (Ленобласть + СПб, подробный, ≈ 570 МБ)

Утилита: [go-pmtiles](https://github.com/protomaps/go-pmtiles) (`go install github.com/protomaps/go-pmtiles@latest`).
Свежие сборки: https://build-metadata.protomaps.dev/builds.json

```
go-pmtiles extract https://build.protomaps.com/20261026.pmtiles lenobl.pmtiles \
  --bbox=27.7,58.4,35.8,61.8 --maxzoom=15
```

`--maxzoom=14` — ≈ 320 МБ, мельче детали.

## Стиль

`android/app/src/main/assets/map/style.json` собирается из пакета `@protomaps/basemaps` (тёмная тема, перекрашенная под «лёд»):

```
npm install @protomaps/basemaps
node gen_style.cjs ../../android/app/src/main/assets/map/style.json
```

Шрифты (Noto Sans, латиница + кириллица) — из https://protomaps.github.io/basemaps-assets/fonts/ в `assets/fonts`.
