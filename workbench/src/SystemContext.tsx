import { useQuery } from '@tanstack/react-query';
import { createContext, ReactNode, useContext, useEffect, useMemo, useState } from 'react';
import { api, CurrentUser, SystemMember, SystemProfile } from './api/client';

const STORAGE_KEY = 'asterism-system';

type SystemContextValue = {
  systems: SystemProfile[];
  systemId: string;
  setSystemId: (systemId: string) => void;
  currentSystem?: SystemProfile;
  currentUser: CurrentUser;
  isAdmin: boolean;
  systemMembers: SystemMember[];
  canManageCurrentSystem: boolean;
  systemAccessLoading: boolean;
  systemAccessError: unknown;
  retrySystemAccess: () => void;
};

const Context = createContext<SystemContextValue | null>(null);

export function SystemProvider({ systems, currentUser, children }: { systems: SystemProfile[]; currentUser: CurrentUser; children: ReactNode }) {
  const [systemId, setSystemIdState] = useState(() => localStorage.getItem(STORAGE_KEY) || '');
  const isAdmin = currentUser.roles.includes('ROLE_ADMIN');
  const members = useQuery({
    queryKey: ['members', systemId],
    queryFn: () => api.members(systemId),
    enabled: Boolean(systemId) && !isAdmin,
    retry: false,
  });

  useEffect(() => {
    // 系统被全部删除后同时清掉旧选择，避免后续请求继续携带失效 ID。
    if (!systems.length) {
      setSystemIdState('');
      localStorage.removeItem(STORAGE_KEY);
      return;
    }
    if (!systems.some((system) => system.systemId === systemId)) {
      const next = systems[0].systemId;
      setSystemIdState(next);
      localStorage.setItem(STORAGE_KEY, next);
    }
  }, [systemId, systems]);

  function setSystemId(value: string) {
    setSystemIdState(value);
    localStorage.setItem(STORAGE_KEY, value);
  }

  const value = useMemo(() => ({
    systems,
    systemId,
    setSystemId,
    currentSystem: systems.find((system) => system.systemId === systemId),
    currentUser,
    isAdmin,
    systemMembers: members.data ?? [],
    canManageCurrentSystem: isAdmin || Boolean(members.data?.some((member) =>
      member.userId === currentUser.userId && ['owner', 'admin'].includes(member.role))),
    systemAccessLoading: !isAdmin && members.isLoading,
    systemAccessError: isAdmin ? null : members.error,
    retrySystemAccess: () => { void members.refetch(); },
  }), [currentUser, isAdmin, members.data, members.error, members.isLoading, members.refetch, systemId, systems]);

  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useCurrentSystem() {
  const value = useContext(Context);
  if (!value) throw new Error('SystemProvider 未初始化');
  return value;
}
