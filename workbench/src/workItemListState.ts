export type WorkItemListState = {
  scope: string;
  status: string;
  q: string;
  sort: string;
  page: number;
};

export type WorkItemNavigationState = {
  workItemList?: WorkItemListState;
};

export const DEFAULT_WORK_ITEM_LIST_STATE: WorkItemListState = {
  scope: 'mine',
  status: '',
  q: '',
  sort: 'updated_desc',
  page: 1,
};

export function readWorkItemListState(state: unknown) {
  return (state as WorkItemNavigationState | null)?.workItemList ?? DEFAULT_WORK_ITEM_LIST_STATE;
}
