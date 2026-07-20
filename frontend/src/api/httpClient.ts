import { getAccessToken, redirectToLogin } from './auth';
import type { ProblemDetails } from './types';

export class ApiProblem extends Error {
  constructor(public problem: ProblemDetails) {
    super(problem.detail || problem.title);
    this.name = 'ApiProblem';
  }
}
export type Fetcher = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

export function createHttpClient(fetcher: Fetcher = fetch, baseUrl = '/api/v1') {
  return async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const token = getAccessToken();
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(init.headers as Record<string, string> | undefined),
    };
    if (token) headers.Authorization = `Bearer ${token}`;

    const response = await fetcher(baseUrl + path, {
      ...init,
      headers,
    });
    if (response.status === 401) redirectToLogin('expired');
    if (!response.ok) {
      let problem: ProblemDetails = {
        title: 'Request failed',
        status: response.status,
        detail: `Request failed (${response.status})`,
      };
      try {
        problem = { ...problem, ...((await response.json()) as ProblemDetails) };
      } catch {
        /* sanitized fallback */
      }
      throw new ApiProblem(problem);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  };
}
