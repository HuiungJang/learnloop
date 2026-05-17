export type HealthResponse = {
  status: string;
};

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? "";

export async function fetchHealth(): Promise<HealthResponse> {
  const response = await fetch(`${apiBaseUrl}/api/health`, {
    headers: {
      Accept: "application/json"
    }
  });

  if (!response.ok) {
    throw new Error(`Health check failed with ${response.status}`);
  }

  return response.json() as Promise<HealthResponse>;
}
