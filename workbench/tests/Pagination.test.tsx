import { fireEvent, render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { Pagination, usePagination } from '../src/components/Pagination';

function Harness({ values, resetKey }: { values: string[]; resetKey: string }) {
  const pagination = usePagination(values, resetKey);
  return (
    <div>
      <div>{pagination.pageItems.map((value) => <span key={value}>{value}</span>)}</div>
      <Pagination total={values.length} page={pagination.page} totalPages={pagination.totalPages} onPageChange={pagination.setPage} />
    </div>
  );
}

test('paginates 21 records and resets when filters change', () => {
  const values = Array.from({ length: 21 }, (_, index) => `item-${index + 1}`);
  const view = render(<Harness values={values} resetKey="all" />);

  expect(screen.getByText('item-20')).toBeInTheDocument();
  expect(screen.queryByText('item-21')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '上一页' })).toBeDisabled();

  fireEvent.click(screen.getByRole('button', { name: '下一页' }));
  expect(screen.getByText('item-21')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '下一页' })).toBeDisabled();

  view.rerender(<Harness values={values} resetKey="completed" />);
  expect(screen.getByText('item-20')).toBeInTheDocument();
  expect(screen.queryByText('item-21')).not.toBeInTheDocument();
});
