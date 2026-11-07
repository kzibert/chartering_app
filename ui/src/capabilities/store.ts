import { useQuery } from '@tanstack/react-query';
import { capabilitiesApi } from '../api/capabilities';
import { readsWithCloud, useParserRoute } from '../parser/route';

/**
 * The deployment's optional features, asked once.
 *
 * One call in place of the three status calls the shell used to make on every load to decide
 * which tabs to draw - one of which counted four tables and asked a model server whether it was
 * up. Those status endpoints still answer their own pages' detailed questions.
 */
export const useCapabilities = () =>
  useQuery({
    queryKey: ['capabilities'],
    queryFn: capabilitiesApi.get,
    staleTime: Infinity,
    retry: false,
  });

/**
 * Whether Intake exists for this person here: a local model, or the cloud offered and their
 * browser holding a key with the cloud chosen. The server applies the same rule
 * (ParserAvailability), so a tab drawn here is a tab whose endpoints answer.
 */
export function useIntakeOn(): boolean {
  const caps = useCapabilities().data;
  const route = useParserRoute();
  if (!caps) return false;
  if (caps.parserCloud && readsWithCloud(route)) return true;
  return caps.parserLocal;
}
