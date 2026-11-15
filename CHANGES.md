# Changes: from the ATC3 driver to v4

The ATC3 driver (last version 2026.10.12.1, for AAPS 3.4.2.6) is kept in the branch `atc3`.
The branch `main` holds the v4 driver.

## Base

- AAPS 4.0.0 instead of 3.4.2.6. The module is `pump/apex` instead of `pump/atc3`.
- Only the driver is published, not the whole of AAPS. The lines AAPS needs to know the pump are
  in `aaps-4.0.0-registration.patch` (7 files). Apply it on the AAPS tag 4.0.0 with `git apply`.
- The screens are written in Compose. The driver keeps its data in its own database.

## Pumps

- One driver for protocol versions 4.10 to 4.12 (firmware 1.0.6.25 to 1.1.1.0). What a pump
  cannot answer is found out when the pump is connected, and the same fact is then taken from
  another answer of the pump.
- The Bluetooth password is used from protocol 4.12 on. Older pumps have no password, and the
  driver works with them without one. The ATC3 driver was made for firmware 1.1.1.0.

## Reworked

- **The pump's clock.** The pump has a clock and no time zone. The driver sets the clock to the
  phone's local time and remembers the offset it was set in. Every time stamp of the pump becomes
  a moment through the offset that was in force when the stamp was made, so records made before a
  change of the time zone keep their true time. The clock is written in the minute after the
  pump's heartbeat.
- **Errors of the pump.** An answer the pump cannot have made is not taken as it is: a record
  dated in the future, a bolus larger than the reservoir held, a temporary basal above the pump's
  own maximum. Such an answer is read again. A heartbeat that the pump sends over its own answer
  is found by its form and taken out of the stream.
- **Errors of the pump's journals.** A record with zeros in place of its end is read again. An
  old record left in a new cell is a faulty cell: what it holds is not taken, and the dose is
  found from the pump's count. A journal that does not continue the copy kept by the driver is
  read a second time, and then whole. The user is told about every case that changes a row.
- **Basal accounting.** The pump is read at its heartbeat. In the normal mode the basal is the
  ordered rate times the time it was in force. In the exact mode the basal is written as the pump
  delivered it, one row for each heartbeat period.
- **Bolus accounting.** A bolus row is made only from a record of the pump's journal. No row is
  made for insulin that no record stands for. Records of boluses given on the pump are written as
  they are.

## Screen and settings

- The heartbeat period is chosen by the user: 1, 2, 3 or 5 minutes, 5 by default.
- The table "AAPS, pump, difference" for boluses, basal and total, with the delivery accuracy in
  percent.
- The maximum bolus and the maximum basal are the AAPS settings. The driver writes them to the
  pump.
- The button "Battery saving mode": extended boluses off, lowest brightness, screen off after
  15 s, alarms by vibration.
- The pump's Bluetooth password is changed to a new random one with one button.
- While extended boluses are allowed on the pump, the driver takes no command.

---

# Изменения: от драйвера ATC3 к v4

Драйвер ATC3 (последняя версия 2026.10.12.1, для AAPS 3.4.2.6) сохранён в ветке `atc3`.
В ветке `main` лежит драйвер v4.

## Основа

- AAPS 4.0.0 вместо 3.4.2.6. Модуль называется `pump/apex` вместо `pump/atc3`.
- Публикуется только драйвер, а не весь AAPS. Строки, по которым AAPS узнаёт помпу, лежат в
  файле `aaps-4.0.0-registration.patch` (7 файлов). Он накладывается на тег AAPS 4.0.0 командой
  `git apply`.
- Экраны написаны на Compose. Данные драйвер хранит в собственной базе.

## Помпы

- Один драйвер для версий протокола с 4.10 по 4.12 (прошивки с 1.0.6.25 по 1.1.1.0). На что
  помпа не отвечает, выясняется при подключении, и тот же факт берётся из другого ответа помпы.
- Пароль Bluetooth используется начиная с протокола 4.12. У более старых помп пароля нет, и
  драйвер работает с ними без него. Драйвер ATC3 был сделан для прошивки 1.1.1.0.

## Переработано

- **Часы помпы.** У помпы есть часы, но нет часового пояса. Драйвер ставит часы по местному
  времени телефона и запоминает смещение, в котором они поставлены. Каждая отметка времени помпы
  переводится в момент через то смещение, которое действовало, когда отметка была сделана,
  поэтому записи, сделанные до смены пояса, сохраняют своё настоящее время. Часы записываются в
  минуту после пульса помпы.
- **Ошибки помпы.** Ответ, которого помпа дать не могла, не принимается как есть: запись с датой
  в будущем, болюс больше, чем было в резервуаре, временный базал выше максимума самой помпы.
  Такой ответ читается заново. Пульс, который помпа посылает поверх собственного ответа,
  находится по своей форме и вынимается из потока.
- **Ошибки журналов помпы.** Запись с нулями вместо окончания читается заново. Старая запись,
  оставшаяся в новой ячейке, считается сбойной ячейкой: её содержимое не берётся, а доза
  находится по счётчику помпы. Журнал, который не продолжает копию, сохранённую драйвером,
  читается второй раз, а затем целиком. О каждом случае, меняющем строку, пользователь получает
  уведомление.
- **Учёт базала.** Помпа читается по её пульсу. В обычном режиме базал — это заказанная ставка,
  умноженная на время её действия. В точном режиме базал пишется так, как помпа его подала: по
  строке на каждый период пульса.
- **Учёт болюсов.** Строка болюса создаётся только из записи журнала помпы. Для инсулина, за
  которым не стоит запись, строка не создаётся. Записи о болюсах, поданных с помпы, пишутся как
  есть.

## Экран и настройки

- Период пульса выбирает пользователь: 1, 2, 3 или 5 минут, по умолчанию 5.
- Таблица «AAPS, помпа, разница» по болюсам, базалу и сумме, с точностью подачи в процентах.
- Максимальный болюс и максимальный базал — это настройки AAPS. Драйвер записывает их в помпу.
- Кнопка «Режим экономии батареи»: растянутые болюсы выключены, минимальная яркость, экран
  гаснет через 15 с, тревоги вибрацией.
- Пароль Bluetooth помпы меняется на новый случайный одной кнопкой.
- Пока на помпе разрешены растянутые болюсы, драйвер не принимает команд.
