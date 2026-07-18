const token = import.meta.env.VITE_METRIC_PLATFORM_API_TOKEN?.trim() || 'admin-token';

export function authenticatedHeaders(headers: Record<string, string> = {}): Record<string, string> {
  return { ...headers, Authorization: `Bearer ${token}` };
}
