# ART VPN

ART VPN для Android на основе готового официального Android-клиента sing-box.

## Основа

- Android VPN-служба, TUN, монитор физических сетей, DNS и lifecycle ядра берутся из [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android).
- Исходная версия зафиксирована на коммите `f87c2ebdb7e34754be751bb5e58926db2fed8f8c`, соответствующем sing-box/libbox 1.14.4.
- Для движка используется официальный [libbox 1.14.4 AAR](https://github.com/singbox-android/libbox/releases/tag/1.14.4).
- ART overlay добавляет профиль ART Auto с обновляемым пулом публичных VLESS-конфигураций и встроенной группой sing-box `urltest`, выбирающей узел по доступности через HTTPS.

## Сборка

GitHub Actions скачивает закреплённый upstream-исходник, накладывает ART-настройки и собирает ARM64 APK. Локальная сборка:

```bash
bash scripts/build-official-art.sh ./upstream
```

Потребуются JDK 21, Android SDK 37, NDK 28.0.13004108 и доступ к GitHub Releases.

## Важные замечания

- Публичные VLESS-серверы принадлежат третьим лицам; они могут исчезать, перегружаться или быть недоступными в конкретной сети. Наличие сервера в списке не гарантирует его работу.
- ART Auto использует sing-box `urltest` для автоматического выбора из поддерживаемых кандидатов. Это лучше, чем выбирать только по TCP ping, но не гарантирует идеальную скорость для всех приложений.
- ART VPN не управляет серверами и не может гарантировать постоянную доступность бесплатных узлов.
- Код производной сборки распространяется по GPL-3.0-or-later. Сохраняются условия и уведомления лицензии upstream-проекта; имя ART VPN не означает официальной связи с SagerNet.

## Исходники и лицензии

- [Официальный Android-клиент sing-box](https://github.com/SagerNet/sing-box-for-android)
- [sing-box core](https://github.com/SagerNet/sing-box)
- [libbox Android AAR](https://github.com/singbox-android/libbox)
- Upstream license: GPL-3.0-or-later, с дополнительным ограничением на использование имени исходного приложения в производных работах.
