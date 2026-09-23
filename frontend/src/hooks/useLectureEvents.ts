import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { SseEvent } from '../types/lecture';
import { getEventsStreamUrl } from '../services/api';

export function useLectureEvents(lectureId?: number, onEvent?: (event: SseEvent) => void) {
  const [latestEvent, setLatestEvent] = useState<SseEvent | null>(null);
  const [isConnected, setIsConnected] = useState(false);
  const queryClient = useQueryClient();

  useEffect(() => {
    if (!lectureId) return;

    const url = getEventsStreamUrl(lectureId);
    const eventSource = new EventSource(url);

    eventSource.onopen = () => {
      setIsConnected(true);
    };

    eventSource.addEventListener('LECTURE_STATUS', (event) => {
      try {
        const data: SseEvent = JSON.parse(event.data);
        setLatestEvent(data);

        // Auto invalidate or update React Query cache for this lecture
        queryClient.invalidateQueries({ queryKey: ['lecture', lectureId] });
        queryClient.invalidateQueries({ queryKey: ['lectures'] });

        if (data.status === 'DONE') {
          queryClient.invalidateQueries({ queryKey: ['summary', lectureId] });
          queryClient.invalidateQueries({ queryKey: ['transcript', lectureId] });
        }

        if (onEvent) {
          onEvent(data);
        }
      } catch (e) {
        console.error('Failed to parse SSE event payload', e);
      }
    });

    eventSource.onerror = () => {
      setIsConnected(false);
      // EventSource automatically attempts to reconnect on error
    };

    return () => {
      eventSource.close();
      setIsConnected(false);
    };
  }, [lectureId, queryClient, onEvent]);

  return { latestEvent, isConnected };
}
