# AI Usage Reflection: Assignment 1

Tool: Claude Code (Anthropic).

## Where and for what

- **Understanding the assignment:** I used AI to go through the assignment and explain the requirements.
- **Building it:** I then worked on the assignment on my own, with my Lab 2 code as the base, and completed the EC2 setup.
- **Strict grading:** once it was done, I asked AI to grade my assignment strictly against the rubric. It found issues, and the day before the deadline it wrote the fixes: latency timed with `System.nanoTime()`, a count of the connections Tomcat closes, the client CPU line, and the check for fewer than 32,000 swipes. It also wrote an Apache HttpClient version of my client, which I kept as a separate experiment outside this repo.
- **Experiments:** I tested and experimented with these and applied the ones I fully understood and could implement. Then, to get a higher throughput, I tried different things, like using a much more powerful EC2 instance for the client.
- **Little's Law:** AI helped recheck my Little's Law calculation.
- **Report:** AI helped format my LaTeX for the PDF.

## Suggestions I accepted (and why)

1. **Timing latency with `System.nanoTime()`:** my latencies were rounded to whole milliseconds, which is too coarse when most swipes take a few milliseconds.
2. **Counting the connections Tomcat closes:** the assignment asks for connection statistics, and my client only printed the number of threads.
3. **The client CPU line and the 32,000-swipe check:** the CPU line shows whether my client or the server is the limit, and the check stops a hang when testing with fewer swipes.

## Suggestions I rejected or corrected (and why)

1. **Switching my client to Apache HttpClient:** I kept it as a separate experiment outside this repo instead of replacing my own client.
2. **Changing my working code directly:** the AI first edited my working code; I told it to keep its changes separate from it.

## A decision I reasoned through myself

**A faster client instance (my idea):** I ran the same code on a much more powerful EC2 client instance. Throughput went from 12,803 to 21,671 swipes/s, and the limit moved from my client to the server.
