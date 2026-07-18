import { getAccessToken, type AuthIdentity, type AuthSession } from './auth';
import { ApiProblem, type Fetcher } from './httpClient';
import type { ProblemDetails } from './types';

const baseUrl = import.meta.env.VITE_METRIC_PLATFORM_API_URL ?? '/api/v1';

async function readProblem(response: Response): Promise<ApiProblem> {
  let problem: ProblemDetails = {
    title: 'Request failed',
    status: response.status,
    detail: `Request failed (${response.status})`,
  };

  try {
    problem = { ...problem, ...((await response.json()) as ProblemDetails) };
  } catch {
    // Use the sanitized fallback.
  }

  return new ApiProblem(problem);
}

export function createAuthApi(fetcher: Fetcher = fetch) {
  return {
    async login(username: string, password: string): Promise<AuthSession> {
      const response = await fetcher(`${baseUrl}/auth/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      if (!response.ok) throw await readProblem(response);
      return response.json() as Promise<AuthSession>;
    },

    async me(): Promise<AuthIdentity> {
      const response = await fetcher(`${baseUrl}/auth/me`, {
        headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` },
      });
      if (!response.ok) throw await readProblem(response);
      return response.json() as Promise<AuthIdentity>;
    },
  };
}

export const authApi = createAuthApi();
