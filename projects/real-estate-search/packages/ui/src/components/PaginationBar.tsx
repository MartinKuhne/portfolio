import React from 'react';
import { Box, Button, Typography } from '@mui/material';
import type { PaginationMeta } from '@real-estate/shared';

export interface PaginationBarProps {
  pagination: PaginationMeta;
  onPreviousPage: () => void;
  onNextPage: () => void;
  loading?: boolean;
}

export function PaginationBar({
  pagination,
  onPreviousPage,
  onNextPage,
  loading = false,
}: PaginationBarProps): React.JSX.Element {
  const isPreviousDisabled = loading || pagination.page <= 1;
  const isNextDisabled =
    loading ||
    pagination.page >= pagination.totalPages ||
    pagination.totalPages === 0;

  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        mt: 3,
        mb: 4,
        flexWrap: 'wrap',
        gap: 2,
      }}
    >
      <Button
        variant="outlined"
        disabled={isPreviousDisabled}
        onClick={onPreviousPage}
      >
        Previous Page
      </Button>

      <Typography variant="body2" color="text.secondary">
        Page {pagination.page} of {pagination.totalPages} ({pagination.totalResults} total listings)
      </Typography>

      <Button
        variant="outlined"
        disabled={isNextDisabled}
        onClick={onNextPage}
      >
        Next Page
      </Button>
    </Box>
  );
}
