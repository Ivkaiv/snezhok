# Сборка «Снежка» 0.19

В репозитории есть исходники `ru.ivkaiv.snowfall`, Android-ресурсы, графика и генераторы тем HONOR. `AndroidManifest.xml` задаёт `versionCode 19`, `versionName 0.19`, минимальный API 26 и целевой API 36.

## APK

Нужны JDK 17 с `javac` и `keytool`, Python 3, `rg`, Android SDK Platform 36 и Build Tools 36.1.0. Укажи локальную папку SDK и запусти сборку из корня репозитория.

```bash
export ANDROID_SDK_ROOT=/path/to/android/sdk
bash build.sh
```

Новая сборка появится в `build/v0.19-*`. Скрипт проверяет SHA-256 индекса и содержимого `exports/garland-modes-v4/` перед упаковкой десяти тем. Эти ресурсы уже включены в репозиторий.

По умолчанию скрипт использует локальный отладочный ключ или создаёт новый. Для обновления уже установленной тестовой сборки укажи прежний отладочный ключ через `SNEZHOK_DEBUG_KEYSTORE`.

### Публичная сборка

Запусти `bash build.sh --release` с переменными `SNEZHOK_KEYSTORE` и `SNEZHOK_KEY_ALIAS`. Пароли считываются из `SNEZHOK_KEYSTORE_PASSWORD` и `SNEZHOK_KEY_PASSWORD`. Режим использует существующий ключ. Скрипт проверяет подпись и выводит SHA-256 сертификата и APK. Храни файлы подписи и пароли вне репозитория.

Android требует тот же сертификат для обновления установленного приложения. Следующие публичные версии должны использовать тот же ключ, что и первая.

## Тема HONOR 6.0.7

Для повторной генерации десяти режимов из исходников используй Python 3.10 или новее, Pillow, NumPy и FFmpeg. Генератор читает три входных пакета проекта из `exports/`. Он сверяет их SHA-256 и записывает результат в новый каталог `exports/garland-modes-v4/`.

- `exports/Snezhok-HONOR-lock-03.hnt`
- `exports/garland-v2/Snezhok-HONOR-garland-warm.hnt`
- `exports/garland-v2/Snezhok-HONOR-garland-color.hnt`

Запускай `python3 build_honor_garland_modes.py` в отдельной рабочей копии с этими входами и свободным путём `exports/garland-modes-v4/`. Для новой версии темы зафиксируй полученный индекс и его SHA-256 в `build.sh` перед сборкой APK. Шрифт `art/NotoSerif-Regular.ttf` используется при отрисовке цифр и распространяется на условиях [SIL OFL 1.1](art/NotoSerif-OFL.txt).
