# Apex TruCare III pump driver for AAPS, version 4

Version 4 is a deeply reworked driver, not an update of the old one (ATC3). The old driver is kept
in the branch `atc3`.

## Important: what the driver was tested on

All tests were made on one pump with firmware 1.1.1.0 (protocol 4.12). The support for earlier
versions (firmware from 1.0.6.25, protocol 4.10 and 4.11) was rebuilt from descriptions and from
partial captures of the exchange. On such pumps something may not fit.

**If you have a pump with firmware 1.0.6.25 or 1.0.6.27, please write to us** at the e-mail address
shown in the profile of the owner of this repository. We need Bluetooth captures of the exchange
with these pumps to find and fix what does not fit. We have a ready instruction: which captures
are needed and how to make them on the phone. It takes about half an hour, and no pump setting has
to be changed. We will send the instruction in answer to your letter. Do not post captures in the
Issues: everyone can see them, and a capture holds the serial number of the pump and data of the
phone.

## What changed

- **Insulin accounting is made anew.** The way boluses and basal are counted is fully changed.
- **Exact mode.** Next to the normal mode there is an exact one: the basal is written as the pump
  delivered it, one row for each heartbeat period.
- **Old protocol versions.** The driver works with protocol 4.10 to 4.12 (firmware 1.0.6.25 to
  1.1.1.0).
- **Errors of the pump.** Rare errors of the pump were found that are serious for the loop. Each
  one is described in the code and handled.
- **Time zones and summer/winter time.** The driver moves the pump's clock when the time zone
  changes and when summer or winter time starts. Records made before the change keep their true
  time.
- **Exact time.** A way to set the pump's clock exactly by the phone's clock was found and built
  in.

## Testing

After the last ATC3 version the driver worked with a pump on the bench for more than 300 hours:
closed loop, boluses, temporary basals, lost links. The pump stood on the bench and was not
connected to a person.

Rare errors of the pump cannot be called up at will, so in the runs they were emulated: the driver
got faulty answers made after the real ones, mixed with the normal work of the loop. The runs were
made in the normal mode and in the exact mode.

## How to build

The repository holds only the driver. Take AAPS at the tag `4.0.0`, copy the folder `pump/apex`
into it and apply `aaps-4.0.0-registration.patch` with `git apply`.

The full list of changes is in `CHANGES.md`. The terms of use are in `DISCLAIMER.md`.

---

# Драйвер помпы Apex TruCare III для AAPS, версия 4

Версия 4 — это кардинально переработанный драйвер, а не обновление прежнего (ATC3). Прежний
драйвер сохранён в ветке `atc3`.

## Важно: на чём драйвер проверен

Все проверки сделаны на одной помпе с прошивкой 1.1.1.0 (протокол 4.12). Поддержка более ранних
версий (прошивки с 1.0.6.25, протокол 4.10 и 4.11) воссоздана по описаниям и по неполным захватам
обмена. На таких помпах возможны несостыковки.

**Если у вас помпа с прошивкой 1.0.6.25 или 1.0.6.27, пожалуйста, напишите нам** на адрес
электронной почты, указанный в профиле владельца этого репозитория. Нам нужны захваты обмена
Bluetooth с этих помп, чтобы найти и убрать несостыковки. У нас есть готовая инструкция: какие
захваты нужны и как их сделать на телефоне. Это занимает около получаса, настройки помпы менять
не нужно. Инструкцию пришлём в ответ на письмо. Захваты в Issues не выкладывайте: Issues видны
всем, а в захвате есть серийный номер помпы и данные телефона.

## Что изменилось

- **Учёт инсулина сделан заново.** Механика учёта болюсов и базала полностью изменена.
- **Точный режим.** Кроме обычного режима есть точный: базал записывается так, как помпа его
  подала, по строке на каждый период пульса.
- **Старые версии протокола связи.** Драйвер работает с протоколом с 4.10 по 4.12 (прошивки с
  1.0.6.25 по 1.1.1.0).
- **Ошибки помпы.** Найдены редкие, но серьёзные для работы цикла ошибки помпы. Каждая описана в
  коде и обрабатывается.
- **Часовые пояса и летнее/зимнее время.** Драйвер переводит часы помпы при смене пояса и при
  переходе на летнее или зимнее время. Записи, сделанные до перевода, сохраняют своё настоящее
  время.
- **Точное время.** Найден и реализован способ точно синхронизировать часы помпы с часами
  телефона.

## Проверка

После последней версии ATC3 драйвер проработал на стенде с помпой более 300 часов: замкнутый цикл,
болюсы, временные базалы, обрывы связи. Помпа стояла на стенде и не была подключена к человеку.

Редкие ошибки помпы нельзя вызвать по желанию, поэтому в прогонах они эмулировались: драйвер
получал ответы с ошибками по образцу настоящих, вперемешку с обычной работой цикла. Прогоны шли и
в обычном, и в точном режиме.

## Как собрать

В репозитории только драйвер. Возьмите AAPS с тегом `4.0.0`, скопируйте в него папку `pump/apex` и
наложите `aaps-4.0.0-registration.patch` командой `git apply`.

Подробный список изменений — в `CHANGES.md`. Условия использования — в `DISCLAIMER.md`.
