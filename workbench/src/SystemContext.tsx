import { createContext, ReactNode, useContext, useEffect, useMemo, useState } from 'react';
import { SystemProfile } from './api/client';

const STORAGE_KEY = 'asterism-system';

type SystemContextValue = {
  systems: SystemProfile[];
  systemId: string;
  setSystemId: (systemId: string) => void;
  currentSystem?: SystemProfile;
};

const Context = createContext<SystemContextValue | null>(null);

export function SystemProvider({ systems, children }: { systems: SystemProfile[]; children: ReactNode }) {
  const [systemId, setSystemIdState] = useState(() => localStorage.getItem(STORAGE_KEY) || '');

  useEffect(() => {
    if (!systems.length) return;
    if (!systems.some((system) => system.systemId === systemId)) {
      setSystemIdState(systems[0].systemId);
    }
  }, [systemId, systems]);

  function setSystemId(value: string) {
    setSystemIdState(value);
    localStorage.setItem(STORAGE_KEY, value);
  }

  useEffect(() => {
    if (systemId) localStorage.setItem(STORAGE_KEY, systemId);
  }, [systemId]);

  const value = useMemo(() => ({
    systems,
    systemId,
    setSystemId,
    currentSystem: systems.find((system) => system.systemId === systemId),
  }), [systemId, systems]);

  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useCurrentSystem() {
  const value = useContext(Context);
  if (!value) throw new Error('SystemProvider 未初始化');
  return value;
}
