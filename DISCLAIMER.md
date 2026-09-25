# Disclaimer

1. **"As is".** The code is provided without warranty of any kind, express or implied, including
   but not limited to warranties of operability, safety, accuracy, fitness for a particular purpose
   and freedom from defects.
2. **Own work.** The driver was developed from the authors' own observations. All data it relies
   on was obtained legitimately from open sources; the protocol description is published at
   https://github.com/russoturisto2026-create/apex-trucare-III-protocol. The algorithms and the
   source code of the Apex pump driver are the authors' own development.
3. **Firmware.** Use of the code with Apex pumps running firmware older than 1.1.1.0 is disabled
   for reasons of patient safety.
4. **Accuracy of accounting.** AAPS accounts for basal insulin continuously, as rate × time. The
   pump delivers it in discrete pulses and counts a pulse at the moment it fires. The difference
   between the two figures has two causes, and both concern the moment of counting, not a loss of
   insulin:
   - compared at any one moment, the figures differ by the part of a pulse that is already due but
     not yet delivered; this closes with the next pulse;
   - the pump's clock and the phone's do not run exactly alike (about 8 seconds in a day and a half
     on the bench), and the moments the pump names and the moments the phone knows lie on different
     scales; a temporary basal row begun on one scale and ended on the other gets a length other
     than the true one. The driver sets the pump's clock from the phone's at start, on a change of
     the loop's mode and every eight hours, so this error stays below a fraction of a pulse, but it
     may persist until the next point of reconciliation. It does not accumulate.

   The bound of the difference is the larger of two pulses and one minute of delivery at the pump's
   maximum basal rate:

   `max(2 × 0.025 U, max basal [U/h] / 60)`

   On the bench, over three days of continuous closed-loop operation, the difference stayed within
   one pulse, 0.025 U, with a single reading of 0.033 U.
5. **Limitation of liability.** To the maximum extent permitted by applicable law, the authors and
   contributors shall not be liable for any damage, direct or indirect, including harm to health or
   life, arising from the use of, or the inability to use, this code.

---

# Отказ от ответственности

1. **«Как есть».** Код предоставляется без каких-либо гарантий, явных или подразумеваемых, включая,
   но не ограничиваясь, гарантии работоспособности, безопасности, точности, пригодности для
   какой-либо цели и отсутствия ошибок.
2. **Собственная разработка.** Драйвер разработан на основе собственных наблюдений авторов. Все
   данные, на которые он опирается, получены легитимно из открытых источников; описание протокола
   опубликовано по адресу https://github.com/russoturisto2026-create/apex-trucare-III-protocol.
   Алгоритмы и программный код драйвера помпы Apex — собственная разработка авторов.
3. **Прошивка.** Использование кода с помпами Apex с прошивкой ниже 1.1.1.0 отключено по
   соображениям безопасности пациентов.
4. **Точность учёта.** AAPS учитывает базальный инсулин непрерывно, как ставка × время. Помпа
   подаёт его дискретными импульсами и засчитывает импульс в момент подачи. У расхождения между
   двумя величинами две причины, и обе относятся к моменту подсчёта, а не к потере инсулина:
   - при сравнении в любой момент времени величины отличаются на ту часть импульса, которая уже
     причитается, но ещё не подана; это закрывается следующим импульсом;
   - часы помпы и телефона идут не строго одинаково (на стенде около 8 секунд за полтора суток),
     а моменты, которые называет помпа, и моменты, которые знает телефон, лежат на разных шкалах;
     строка временного базала, начатая по одной шкале и законченная по другой, получает длину,
     отличную от истинной. Драйвер сверяет часы помпы с телефоном при запуске, при смене режима
     петли и раз в восемь часов, поэтому такая ошибка не превышает доли импульса, но может
     держаться до следующей точки сверки. Она не накапливается.

   Граница расхождения — большее из двух импульсов и одной минуты подачи при максимальном базале
   помпы:

   `max(2 × 0.025 Ед, макс. базал [Ед/ч] / 60)`

   На стенде за трое суток непрерывной работы в замкнутом цикле расхождение держалось в пределах
   одного импульса, 0.025 Ед, с единственным показанием 0.033 Ед.
5. **Ограничение ответственности.** В максимальной степени, допускаемой применимым правом, авторы и
   участники не несут ответственности за какой-либо вред, прямой или косвенный, включая вред
   здоровью и жизни, возникший из использования или невозможности использования этого кода.
