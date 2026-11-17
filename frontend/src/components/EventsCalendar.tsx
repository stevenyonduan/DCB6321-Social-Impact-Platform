import { useState } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import type { MyRegistration } from '../api/registrations';

const weekdays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const monthLabel = new Intl.DateTimeFormat(undefined, {
  month: 'long',
  year: 'numeric',
});
const dayLabel = new Intl.DateTimeFormat(undefined, { dateStyle: 'full' });

function localDayKey(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function EventsCalendar({
  events,
  now,
  onOpen,
}: {
  events: MyRegistration[];
  now: number;
  onOpen: (event: MyRegistration, trigger: HTMLButtonElement) => void;
}) {
  const [month, setMonth] = useState(() => {
    const today = new Date();
    return new Date(today.getFullYear(), today.getMonth(), 1, 12);
  });
  const year = month.getFullYear();
  const monthIndex = month.getMonth();
  const offset = month.getDay();
  const daysInMonth = new Date(year, monthIndex + 1, 0, 12).getDate();
  const weeks = Math.ceil((offset + daysInMonth) / 7);
  const todayKey = localDayKey(new Date(now));
  const byDate = new Map<string, MyRegistration[]>();
  for (const event of events) {
    const key = localDayKey(new Date(event.eventStart));
    const dayEvents = byDate.get(key);
    if (dayEvents) dayEvents.push(event);
    else byDate.set(key, [event]);
  }

  return (
    <section
      className="events-calendar profile-card"
      aria-labelledby="events-month"
    >
      <div className="events-month-header">
        <h2 id="events-month" aria-live="polite">
          {monthLabel.format(month)}
        </h2>
        <div className="events-month-actions">
          <button
            className="button dialog-cancel"
            type="button"
            aria-label="Previous month"
            onClick={() => setMonth(new Date(year, monthIndex - 1, 1, 12))}
          >
            <ChevronLeft aria-hidden="true" />
          </button>
          <button
            className="button dialog-cancel"
            type="button"
            aria-label="Next month"
            onClick={() => setMonth(new Date(year, monthIndex + 1, 1, 12))}
          >
            <ChevronRight aria-hidden="true" />
          </button>
        </div>
      </div>
      <table aria-labelledby="events-month">
        <thead>
          <tr>
            {weekdays.map((day) => (
              <th key={day} scope="col">
                {day}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: weeks }, (_, week) => (
            <tr key={week}>
              {weekdays.map((weekday, day) => {
                const date = new Date(
                  year,
                  monthIndex,
                  week * 7 + day + 1 - offset,
                  12,
                );
                const key = localDayKey(date);
                const inMonth = date.getMonth() === monthIndex;
                return (
                  <td
                    key={weekday}
                    className={inMonth ? undefined : 'events-other-month'}
                  >
                    <time
                      className="events-day-number"
                      dateTime={key}
                      aria-label={dayLabel.format(date)}
                      aria-current={key === todayKey ? 'date' : undefined}
                    >
                      {date.getDate()}
                    </time>
                    {inMonth &&
                      byDate.get(key)?.map((event) => (
                        <button
                          key={event.id}
                          type="button"
                          className={
                            Date.parse(event.eventEnd) <= now
                              ? 'events-calendar-item events-calendar-past'
                              : 'events-calendar-item'
                          }
                          title={event.title}
                          onClick={(click) =>
                            onOpen(event, click.currentTarget)
                          }
                        >
                          {event.title}
                        </button>
                      ))}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
