# Сборка «Снежка» 0.18

В репозитории есть исходники `ru.ivkaiv.snowfall`, Android-ресурсы, графика и генераторы тем HONOR. `AndroidManifest.xml` задаёт `versionCode 18`, `versionName 0.18`, минимальный API 26 и целевой API 36.

## APK

Нужны JDK 17 с `javac` и `keytool`, Python 3, `rg`, Android SDK Platform 36 и Build Tools 36.1.0. Укажи локальную папку SDK и запусти сборку из корня репозитория.

```bash
export ANDROID_SDK_ROOT=/path/to/android/sdk
bash build.sh
```

Новая сборка появится в `build/v0.18-*`. Скрипт проверяет SHA-256 индекса и содержимого `exports/garland-modes-v4/` перед упаковкой десяти тем. Эти ресурсы уже включены в репозиторий.

При первом запуске скрипт создаёт локальный отладочный ключ. Для обновления уже установленного APK Android требует ключ предыдущей сборки. Он хранится отдельно у владельца.

## Тема HONOR 6.0.7

Для повторной генерации десяти режимов из исходников используй Python 3.10 или новее, Pillow, NumPy и FFmpeg. Генератор читает три входных пакета проекта из `exports/`. Он сверяет их SHA-256 и записывает результат в новый каталог `exports/garland-modes-v4/`.

- `exports/Snezhok-HONOR-lock-03.hnt`
- `exports/garland-v2/Snezhok-HONOR-garland-warm.hnt`
- `exports/garland-v2/Snezhok-HONOR-garland-color.hnt`

Запускай `python3 build_honor_garland_modes.py` в отдельной рабочей копии с этими входами и свободным путём `exports/garland-modes-v4/`. Для новой версии темы зафиксируй полученный индекс и его SHA-256 в `build.sh` перед сборкой APK. Шрифт `art/NotoSerif-Regular.ttf` используется при отрисовке цифр и распространяется на условиях [SIL OFL 1.1](art/NotoSerif-OFL.txt).
