# Changes: v4 from 2026.11.27.3 to 2026.11.28.3

## Basal at the pump's midnight

- **The basal period closes by the old day.** The statistics are read a few seconds after the
  heartbeat mark. A portion given in those seconds after midnight is already in the pump's new
  day. Before, the period that ended at midnight took it, so AAPS held it in the old day and the
  pump in the new one. Now the period closes by the records of the old days only, and the next
  one begins from nothing in the new day.
- **A new day of nothing is not a broken record.** A day the pump just opened holds nothing by
  its nature. It is no longer read again: the second read let a portion given after midnight
  into the heartbeat.
- **The screen takes the new day as nothing at the heartbeat of midnight.** From 00:00 to 00:05
  it no longer shows a gap of one portion.

## Daily statistics

- **The statistics are read once at a heartbeat.** Before, they were read once more after the
  bolus journal. No bolus runs at a reconciled heartbeat, so the second read only let basal
  portions given after the mark into the heartbeat.

# Changes: v4 from 2026.11.27.2 to 2026.11.27.3

## Start

- **A record AAPS already holds counts in the day of its stamp.** The pump counts a bolus in the
  day of its stamp. A record stamped just after midnight, while the pump's clock stood in a time
  zone ahead, has its row in AAPS before midnight. The check of the days now takes the stamp, so
  the day the record belongs to is not left short.

# Changes: v4 from 2026.11.27.1 to 2026.11.27.2

## Start

- **A journal read whole keeps the rows AAPS already holds.** A record whose row is in AAPS is
  taken as sound at the time of that row, and nothing is written for it. Before, when the
  driver's own data were lost, the driver did not know the time zone of such a record and wrote
  it again an hour off.
- **The newest bolus in AAPS is found by its time.** Before, the first row of the list was taken,
  and it was the oldest one, so later records were written over their own rows.

# Changes: v4 from 2026.11.18.3 to 2026.11.27.1

## Bolus accounting

- **The driver's own table of boluses.** The driver keeps one cell for each record of the pump's
  journal and for each bolus of its own. The row of our bolus is written to AAPS at once and is
  then corrected by the pump's record.
- **One reconciliation at the heartbeat.** Every bolus row is held against the pump's daily
  statistics. If the journal and the statistics do not agree, the user is told the span and the
  difference, and the loop is opened.
- **The key of a row comes from the pump's record:** the minute, the amount asked for, the amount
  delivered.
- **Faulty records.** A sound record is written as it is. A faulty one gets its dose from the move
  of the statistics. Several faulty records of one day are written as one row with their sum, at
  the first of them.
- **A flickering record** (no date or nothing delivered) makes the journal read again. A record
  that is faulty in the same way at every read is taken as a faulty cell.
- **Our bolus with no record** is written by the pump's count, and the user is told.
- **A record that delivered more than was asked for** is taken as faulty.
- **A bolus between two steps of the pump** is rounded to the pump's step.

## Start

- **A start reconciliation in place of the clean start.** The driver finds the last bolus in AAPS
  and loads only what is newer, looks for faulty records and holds the days against the
  statistics.
- **Three failed starts in a row:** boluses of the loop are not given, and the user is told.
- **A journal read whole** is dated by the log of clock changes.
- **Clean-up of the log of clock changes.** At the start the driver removes the lines whose count
  of bolus records is higher than the driver's own. Before, such a line gave its old offset to
  new records, and a record made after a change of the time zone stood an hour off and was taken
  as faulty.
- **An error of the driver itself** in settling the journal stops the boluses of the loop, and
  the user is told.

## Daily statistics

- The driver keeps a copy of the days and renews it at every heartbeat. At a sign of a flicker
  the day is read again.
- A faulty record at midnight stands in the day the statistics give it.

## Pumps

- **Only pumps that give the daily statistics** (protocol 4.10 and newer). The driver does not
  take an older pump and tells the user why.
- What a pump can do is set by its protocol version, with no trial requests. The section
  "Diagnosis" is removed.
- Until the firmware version is read, the driver checks nothing and sends no command.

## Basal

- The last finished temporary basal is read only when the statistics show there was one.
- In the exact mode, the temporary basal data are not read at a heartbeat that closes its period.

## Screen and settings

- The switch "Insulin change by refill": when the reservoir grows by 5 U or more, an insulin
  change is written to AAPS. It is off by default.
- The day total on the screen is the sum of its lines. The day comparison starts at the first
  heartbeat.

## Code

- The folder `deep` is gone: each rule is where it is used. The files are laid out by what they
  keep account of: boluses, basal, statistics, clock, writing to AAPS, store, heartbeat.
- The driver's database is version 13, with migrations from version 8.

# Changes: v4 from 2026.11.18.2 to 2026.11.18.3

## Link

- **An empty answer is a state.** The pump may answer with zeros or with a short frame when it
  has nothing to tell: no temporary basal, an empty journal. Before, such an answer was asked
  again and counted toward "the pump does not work". Now it is taken at once as what the pump
  says.
- **An answer that cannot be read is no answer.** Before, the refused frame could be handed on
  as if it were an answer.
- **The firmware version.** If it cannot be read, the driver works on as with a pump that has no
  password and asks again at the next link. The user is told once.
- **The notice "the pump gives no usable answer"** is taken away only after a heartbeat
  reconciled in full, not after the first usable answer.

## Accounting

- **Journals.** The count of records comes from the frames. A count of 0 is an empty journal;
  frames past the count are no records. After an empty journal every later record is new.
- **Daily statistics.** If the pump gives them empty, the driver takes the count of the status.
- **The day on the screen** is compared from whole minutes: the heartbeat of minute M closes the
  data up to the end of minute M-1, and the rows of minute M belong to the next period.

## Code

- A branch of the faulty-cell rule that was never taken is removed.

# Changes: v4 from 2026.11.15.1 to 2026.11.18.2

## Accounting

- **Whether a bolus runs.** Every heartbeat first reads the status and reads the pump's count
  again after 2 s (6 s at the slow bolus speed). While a bolus runs, nothing is read or kept, and
  no basal period by the count is closed or begun. The pump has no sign of a running bolus, and
  the record of a bolus comes only after its end: before, a period could hold part of a bolus,
  and the record counted it again.
- **Our bolus.** It is confirmed only by its own record. The top of the journal is read before
  the bolus and up to three times after it. A record that comes late is still ours.
- **The upper bound of the basal by the count.** A period is never above what the pump's maximum
  basal gives. The rest is written as a bolus, and the user is told.
- **No rows are lost.** The reservoir bound follows the newest status, so a bolus after a
  cartridge change is written. The journal rows and the driver's marks are written in one
  transaction. The journal starts anew only when the last reconciliation is older than both the
  time the insulin acts and five hours.
- **Temporary basal.** A cancel that could not read the temporary basal data settles the status
  first. A temporary basal above a new maximum is cancelled before the limits are written. A
  temporary basal never starts before the minute of the command.

## Link

- **The pump does not work.** After three requests in a row with no usable answer (no answer, a
  wrong checksum, a frame that cannot be read) the driver takes the pump as not working. No
  request is sent until its heartbeat comes or a new link is made. The user is told once.
- **The pump's clock** is written only after a full reconciliation.

## Screen and settings

- The heartbeat period: 2, 3, 5 or 6 minutes. One minute is removed.
- The section "Diagnosis": the firmware, the protocol and what the pump can read.
- The day is compared only after a full reconciliation, and its time is shown.

## Code

- Everything foreign or unexpected is in the files `deep/*Deep`. Each one tells the problem, when
  it applies and the way out that was chosen.
- Clean-up after a review by the upstream rules: dead code and unused columns are removed (the
  driver's database is version 8 with a migration from 7), duplicates are merged into one rule to
  read again, comments are fixed, the screen takes its spacing from the theme.

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

- The heartbeat period is chosen by the user: 2, 3, 5 or 6 minutes, 5 by default.
- The table "AAPS, pump, difference" for boluses, basal and total, with the delivery accuracy in
  percent.
- The maximum bolus and the maximum basal are the AAPS settings. The driver writes them to the
  pump.
- The button "Battery saving mode": extended boluses off, lowest brightness, screen off after
  15 s, alarms by vibration.
- The pump's Bluetooth password is changed to a new random one with one button.
- While extended boluses are allowed on the pump, the driver takes no command.

---

# Изменения: v4 с 2026.11.27.3 до 2026.11.28.3

## Базал на полуночи помпы

- **Период базала закрывается по старому дню.** Статистика читается через несколько секунд после
  отметки пульса. Порция, поданная в эти секунды после полуночи, у помпы уже в новом дне. Раньше
  её забирал период, который кончился в полночь, и в AAPS она стояла в старом дне, а у помпы — в
  новом. Теперь период закрывается только по записям старых дней, а следующий начинается с нуля
  в новом дне.
- **Новый пустой день — не сбойная запись.** День, который помпа только что открыла, пуст по
  своей природе. Он больше не перечитывается: повторное чтение пускало в пульс порцию, поданную
  после полуночи.
- **Экран на пульсе полуночи считает новый день пустым.** С 00:00 до 00:05 он больше не
  показывает разницу в одну порцию.

## Суточная статистика

- **Статистика читается на пульсе один раз.** Раньше она читалась ещё раз после журнала болюсов.
  На сверенном пульсе болюс не идёт, так что второе чтение только пускало в пульс порции базала,
  поданные после отметки.

# Изменения: v4 с 2026.11.27.2 до 2026.11.27.3

## Запуск

- **Запись, которая уже есть в AAPS, считается в дне своего штампа.** Помпа относит болюс к дню
  его штампа. У записи со штампом сразу после полуночи, когда часы помпы стояли в поясе впереди,
  строка в AAPS стоит до полуночи. Сверка дней теперь берёт штамп, и день, к которому запись
  относится, не остаётся с недостачей.

# Изменения: v4 с 2026.11.27.1 до 2026.11.27.2

## Запуск

- **Журнал, прочитанный целиком, не трогает строки, которые уже есть в AAPS.** Запись, чья строка
  есть в AAPS, считается исправной и стоит на времени этой строки, для неё ничего не пишется.
  Раньше, если собственные данные драйвера были потеряны, он не знал пояса такой записи и писал
  её заново со сдвигом на час.
- **Последний болюс в AAPS ищется по времени.** Раньше бралась первая строка списка, а это была
  самая старая, и более поздние записи писались поверх своих же строк.

# Изменения: v4 с 2026.11.18.3 до 2026.11.27.1

## Учёт болюсов

- **Своя таблица болюсов.** Драйвер хранит по ячейке на каждую запись журнала помпы и на каждый
  свой болюс. Строка нашего болюса пишется в AAPS сразу и потом уточняется по записи помпы.
- **Одна сверка на пульсе.** Каждая строка болюса сверяется с суточной статистикой помпы. Если
  журнал и статистика не сходятся, пользователь получает уведомление с промежутком и разницей,
  петля размыкается.
- **Ключ строки — из записи помпы:** минута, заказ, подача.
- **Сбойные записи.** Исправная запись идёт как есть. Сбойная получает дозу по сдвигу статистики.
  Несколько сбойных записей за один день пишутся одной строкой с их суммой, у первой из них.
- **Мерцающая запись** (без даты или без подачи) — журнал читается ещё раз. Запись, сбойная
  одинаково на всех чтениях, принимается как сбойная ячейка.
- **Наш болюс без записи** пишется по счётчику помпы, пользователь получает уведомление.
- **Запись, где подано больше заказанного,** считается сбойной.
- **Болюс между двумя шагами помпы** округляется до её шага.

## Запуск

- **Сверка при запуске вместо чистого старта.** Драйвер находит последний болюс в AAPS и
  догружает только то, что новее, ищет сбойные записи и сверяет дни со статистикой.
- **Три неудачных запуска подряд:** болюсы петли не подаются, пользователь получает уведомление.
- **Журнал, прочитанный целиком,** датируется по журналу перевода часов.
- **Чистка журнала перевода часов.** При запуске удаляются строки, у которых счётчик записей
  болюсов больше, чем у драйвера. Раньше такая строка отдавала свой старый сдвиг новым записям, и
  запись после смены пояса вставала на час не туда и считалась сбойной.
- **Ошибка самого драйвера** при разборе журнала останавливает болюсы петли, пользователь
  получает уведомление.

## Суточная статистика

- Драйвер хранит копию дней и обновляет её на каждом пульсе. При признаке мерцания день читается
  снова.
- Сбойная запись на границе полуночи встаёт в тот день, куда её относит статистика.

## Помпы

- **Только помпы с суточной статистикой** (протокол 4.10 и выше). Помпу старше драйвер не
  принимает и говорит пользователю почему.
- Что умеет помпа, определяется версией протокола, без пробных запросов. Раздел «Диагностика»
  убран.
- Пока версия прошивки не прочитана, драйвер ничего не сверяет и команд не шлёт.

## Базал

- Последний завершённый временный базал читается, только когда статистика показывает, что он был.
- В точном режиме на пульсе, закрывающем период, данные временного базала не читаются.

## Экран и настройки

- Переключатель «Смена инсулина по заправке»: при росте резервуара на 5 Ед и больше в AAPS
  пишется смена инсулина. По умолчанию выключен.
- Итог дня на экране — сумма его строк. Сравнение дня начинается с первого пульса.

## Код

- Папки `deep` больше нет: каждое правило лежит там, где применяется. Файлы разложены по предмету
  учёта: болюсы, базал, статистика, часы, запись в AAPS, хранилище, пульс.
- База драйвера — версия 13, с переходами с версии 8.

# Изменения: v4 с 2026.11.18.2 до 2026.11.18.3

## Связь

- **Пустой ответ — это состояние.** Помпа может ответить нулями или коротким кадром, когда ей
  нечего сообщить: нет временного базала, журнал пуст. Раньше такой ответ запрашивался заново и
  шёл в счёт «помпа не работает». Теперь он сразу принимается как то, что помпа сообщила.
- **Ответ, который не разбирается, — не ответ.** Раньше отвергнутый кадр мог быть передан дальше
  как ответ.
- **Версия прошивки.** Если она не читается, драйвер работает дальше, как с помпой без пароля, и
  спрашивает снова при следующей связи. Пользователь получает одно уведомление.
- **Уведомление «помпа не даёт пригодного ответа»** снимается только после полностью сведённого
  пульса, а не после первого годного ответа.

## Учёт

- **Журналы.** Число записей берётся из кадров. Ноль — пустой журнал; кадры сверх числа записями
  не считаются. После пустого журнала каждая следующая запись — новая.
- **Суточная статистика.** Если помпа отдаёт её пустой, драйвер берёт счётчик из статуса.
- **Сравнение за день на экране** ведётся от целых минут: пульс минуты M закрывает данные по
  конец минуты M−1, а строки минуты M относятся к следующему периоду.

## Код

- Убрана ветка правила сбойной ячейки, которая никогда не выполнялась.

# Изменения: v4 с 2026.11.15.1 до 2026.11.18.2

## Учёт

- **Идёт ли болюс.** Каждый пульс первым читает статус и перечитывает счётчик помпы через 2 с
  (6 с на медленной скорости болюса). Пока болюс идёт, ничего не читается и не сохраняется, и
  период базала по счётчику не закрывается и не начинается. У помпы нет признака идущего болюса,
  а запись о болюсе появляется только после его конца: раньше в период мог попасть кусок болюса,
  и запись считала его второй раз.
- **Наш болюс.** Подтверждается только своей записью. Верх журнала читается до болюса и до трёх
  раз после него. Запись, которая пришла с опозданием, остаётся нашей.
- **Потолок базала по счётчику.** Период не больше, чем даёт максимальный базал помпы. Излишек
  пишется болюсом, пользователь получает уведомление.
- **Строки не теряются.** Граница резервуара берётся по самому свежему статусу, поэтому болюс
  после смены картриджа записывается. Строки журнала и отметки драйвера пишутся одной
  транзакцией. Журнал начинается заново, только если последняя сверка старше срока действия
  инсулина и пяти часов.
- **Временный базал.** Отмена, у которой не прочитались данные временного базала, сначала
  сверяет статус. Временный базал выше нового максимума отменяется до записи лимитов. Начало
  временного базала не раньше минуты команды.

## Связь

- **Помпа не работает.** Три запроса подряд без годного ответа (нет ответа, неверная контрольная
  сумма, кадр не разбирается) — драйвер считает, что помпа не работает. Запросы не шлются, пока
  не придёт её пульс или не будет новой связи. Пользователь получает одно уведомление.
- **Часы помпы** записываются только после полной сверки.

## Экран и настройки

- Период пульса: 2, 3, 5 или 6 минут. Одна минута убрана.
- Раздел «Диагностика»: прошивка, протокол и что помпа умеет читать.
- Сравнение за день делается только после полной сверки, показано её время.

## Код

- Всё чужое и неожиданное вынесено в файлы `deep/*Deep`. В каждом описано, какая проблема, когда
  применяется и какой выход выбран.
- Чистка после разбора по правилам апстрима: убран мёртвый код и неиспользуемые столбцы (база
  драйвера версии 8 с переходом с 7), дубли слиты в одно правило перечитывания, исправлены
  комментарии, экран берёт отступы из темы.

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

- Период пульса выбирает пользователь: 2, 3, 5 или 6 минут, по умолчанию 5.
- Таблица «AAPS, помпа, разница» по болюсам, базалу и сумме, с точностью подачи в процентах.
- Максимальный болюс и максимальный базал — это настройки AAPS. Драйвер записывает их в помпу.
- Кнопка «Режим экономии батареи»: растянутые болюсы выключены, минимальная яркость, экран
  гаснет через 15 с, тревоги вибрацией.
- Пароль Bluetooth помпы меняется на новый случайный одной кнопкой.
- Пока на помпе разрешены растянутые болюсы, драйвер не принимает команд.
