import { createContext, createResource, useContext, type Accessor, type JSX } from "solid-js";
import { getCodeProjectScopes, type CodeProjectScope } from "./api";

export type CodeCatalogStatus = "loading" | "ready" | "error";

export type CodeNavigationContextValue = {
  scopes: Accessor<CodeProjectScope[]>;
  catalogStatus: Accessor<CodeCatalogStatus>;
  catalogError: Accessor<string | null>;
  refreshCatalog: () => void;
};

const EMPTY_CONTEXT: CodeNavigationContextValue = {
  scopes: () => [],
  catalogStatus: () => "ready",
  catalogError: () => null,
  refreshCatalog: () => undefined,
};

export const CodeNavigationContext = createContext<CodeNavigationContextValue>(EMPTY_CONTEXT);

export function CodeNavigationProvider(props: { children?: JSX.Element }) {
  const [catalog, { refetch }] = createResource(
    async () => {
      try {
        return { scopes: await getCodeProjectScopes(), status: "ready" as const, error: null };
      } catch {
        return {
          scopes: [],
          status: "error" as const,
          error: "Eligible project roots could not be loaded.",
        };
      }
    },
    { initialValue: { scopes: [], status: "loading" as const, error: null } },
  );

  const value: CodeNavigationContextValue = {
    scopes: () => catalog().scopes,
    catalogStatus: () => (catalog.loading ? "loading" : catalog().status),
    catalogError: () => catalog().error,
    refreshCatalog: () => {
      void refetch();
    },
  };
  return (
    <CodeNavigationContext.Provider value={value}>{props.children}</CodeNavigationContext.Provider>
  );
}

export function useCodeNavigation(): CodeNavigationContextValue {
  return useContext(CodeNavigationContext);
}
