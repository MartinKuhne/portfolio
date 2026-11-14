import React from 'react';
import {
  Box,
  Card,
  CardContent,
  CircularProgress,
  Stack,
  Typography,
} from '@mui/material';
import type { Listing } from '@real-estate/shared';

const currencyFormatter = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  maximumFractionDigits: 0,
});

export interface ListingResultsProps {
  listings: Listing[];
  loading: boolean;
  hasSearched: boolean;
  hasError: boolean;
}

export function ListingResults({
  listings,
  loading,
  hasSearched,
  hasError,
}: ListingResultsProps): React.JSX.Element {
  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', my: 4 }}>
        <CircularProgress role="progressbar" />
      </Box>
    );
  }

  if (!hasError && listings.length === 0 && hasSearched) {
    return (
      <Box sx={{ my: 4, textAlign: 'center' }}>
        <Typography variant="h6" color="text.secondary" gutterBottom>
          No listings matched your search criteria.
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Try broadening your price range, reducing minimum bedrooms, or clearing the city or keyword filter.
        </Typography>
      </Box>
    );
  }

  return (
    <Stack spacing={2} sx={{ my: 2 }}>
      {listings.map((listing) => (
        <Card key={listing.id} variant="outlined">
          <CardContent>
            <Typography variant="h6" component="div">
              {listing.address}
            </Typography>
            <Typography color="text.secondary" variant="body2" sx={{ mb: 1 }}>
              {listing.city}
            </Typography>
            <Typography variant="h5" color="primary" sx={{ mb: 1, fontWeight: 'bold' }}>
              {currencyFormatter.format(listing.price)}
            </Typography>
            <Typography variant="body1">
              {listing.bedrooms} {listing.bedrooms === 1 ? 'bedroom' : 'bedrooms'}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ my: 0.5 }}>
              Relevance: {listing.relevanceScore.toFixed(2)}
            </Typography>
            <Typography variant="body2" sx={{ mt: 1 }}>
              {listing.description}
            </Typography>
          </CardContent>
        </Card>
      ))}
    </Stack>
  );
}
