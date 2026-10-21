# NordKey — приложение для Android

Одно приложение для клиентов и сотрудников NordKey. Вход — через Telegram-бота @Nordkeybot, экраны зависят от роли
(Владелец, Админ, Техник, Поддержка, Клиент).

- Сервер: `nk-api` на srv1 (HTTPS, сертификат проверяется по отпечатку SHA-256, см. `net/Api.kt`).
- Сборка: GitHub Actions (`.github/workflows/build.yml`) при каждом изменении в `main`.
- Свежая версия для клиентов: https://github.com/Wardenspot/vps-manager/releases/latest/download/NordKey.apk

Ключ подписи хранится только в секретах репозитория (`NK_KEYSTORE_B64`, `NK_KEYSTORE_PASSWORD`) и в резервной копии у владельца.
Без него новые версии не установятся поверх старых — не теряйте его.
