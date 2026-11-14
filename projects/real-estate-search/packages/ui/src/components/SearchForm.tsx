import React from 'react';
import { Box, Button, Grid, MenuItem, TextField } from '@mui/material';

const PAGE_SIZE_OPTIONS = ['5', '10', '20', '50', '100'] as const;
const MIN_BEDROOMS_OPTIONS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10'] as const;

export interface SearchFormState {
  minPrice: string;
  maxPrice: string;
  minBedrooms: string;
  city: string;
  keyword: string;
  targetBudget: string;
  pageSize: string;
}

export interface SearchFormProps {
  values: SearchFormState;
  onChange: (field: keyof SearchFormState, value: string) => void;
  onSubmit: (e?: React.FormEvent) => void;
  onReset: () => void;
}

export function SearchForm({
  values,
  onChange,
  onSubmit,
  onReset,
}: SearchFormProps): React.JSX.Element {
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit(e);
  };

  const handleChange =
    (field: keyof SearchFormState) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      onChange(field, e.target.value);
    };

  return (
    <Box component="form" onSubmit={handleSubmit} noValidate sx={{ mb: 3 }}>
      <Grid container spacing={2}>
        <Grid item xs={12} sm={6} md={3}>
          <TextField
            fullWidth
            id="min-price"
            label="Min Price"
            value={values.minPrice}
            onChange={handleChange('minPrice')}
            inputProps={{ 'aria-label': 'Min Price' }}
          />
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          <TextField
            fullWidth
            id="max-price"
            label="Max Price"
            value={values.maxPrice}
            onChange={handleChange('maxPrice')}
            inputProps={{ 'aria-label': 'Max Price' }}
          />
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          <TextField
            select
            fullWidth
            id="min-bedrooms"
            label="Min Bedrooms"
            value={values.minBedrooms}
            onChange={handleChange('minBedrooms')}
            SelectProps={{ displayEmpty: true }}
            InputLabelProps={{ shrink: true }}
          >
            <MenuItem value="">Any</MenuItem>
            {MIN_BEDROOMS_OPTIONS.map((count) => (
              <MenuItem key={count} value={count}>
                {count}
              </MenuItem>
            ))}
          </TextField>
        </Grid>
        <Grid item xs={12} sm={6} md={3}>
          <TextField
            fullWidth
            id="city"
            label="City"
            value={values.city}
            onChange={handleChange('city')}
            inputProps={{ 'aria-label': 'City' }}
          />
        </Grid>
        <Grid item xs={12} sm={6} md={4}>
          <TextField
            fullWidth
            id="keyword"
            label="Keyword"
            value={values.keyword}
            onChange={handleChange('keyword')}
            inputProps={{ 'aria-label': 'Keyword' }}
          />
        </Grid>
        <Grid item xs={12} sm={6} md={4}>
          <TextField
            fullWidth
            id="target-budget"
            label="Target Budget"
            value={values.targetBudget}
            onChange={handleChange('targetBudget')}
            inputProps={{ 'aria-label': 'Target Budget' }}
          />
        </Grid>
        <Grid item xs={12} sm={6} md={4}>
          <TextField
            select
            fullWidth
            id="page-size"
            label="Page Size"
            value={values.pageSize}
            onChange={handleChange('pageSize')}
          >
            {PAGE_SIZE_OPTIONS.map((size) => (
              <MenuItem key={size} value={size}>
                {size}
              </MenuItem>
            ))}
          </TextField>
        </Grid>
        <Grid item xs={12} sx={{ display: 'flex', gap: 2, mt: 1 }}>
          <Button
            type="submit"
            variant="contained"
            color="primary"
          >
            Search
          </Button>
          <Button
            type="button"
            variant="outlined"
            color="secondary"
            onClick={onReset}
          >
            Reset Filters
          </Button>
        </Grid>
      </Grid>
    </Box>
  );
}
