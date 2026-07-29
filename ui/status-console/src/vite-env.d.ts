/// <reference types="vite/client" />

/** The build-time variables the console reads. Declared so a typo is a type error, not a runtime undefined. */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_CLUSTER_ID?: string;
  readonly VITE_KEYCLOAK_URL?: string;
  readonly VITE_KEYCLOAK_REALM?: string;
  readonly VITE_KEYCLOAK_CLIENT_ID?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
