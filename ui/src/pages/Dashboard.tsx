import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, Col, Empty, List, Row, Space, Statistic, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { vesselsApi } from '../api/vessels';
import { companiesApi } from '../api/companies';
import { contactsApi } from '../api/contacts';
import VesselDrawer from './vessels/VesselDrawer';
import VesselForm from './vessels/VesselForm';
import CompanyDrawer from './companies/CompanyDrawer';
import CompanyForm from './companies/CompanyForm';
import { clearRecent, useRecent, type RecentEntry, type RecentKind } from '../recent/store';
import type { CompanyResponse, VesselResponse } from '../api/types';

export default function Dashboard() {
  const navigate = useNavigate();

  const counts = useQuery({
    queryKey: ['dashboard', 'counts'],
    queryFn: async () => {
      const [v, c, ct] = await Promise.all([
        vesselsApi.search({ size: 1 }),
        companiesApi.search({ size: 1 }),
        contactsApi.search({ size: 1 }),
      ]);
      return { vessels: v.totalElements, companies: c.totalElements, contacts: ct.totalElements };
    },
  });

  const recentVessels = useRecent('vessel');
  const recentCompanies = useRecent('company');
  const recentPeople = useRecent('person');

  // The dashboard opens the same drawers the list pages use, so a recent item can be
  // picked up where it was left off without navigating away.
  const [vesselId, setVesselId] = useState<number>();
  const [companyId, setCompanyId] = useState<number>();
  const [companyTab, setCompanyTab] = useState<'vessels' | 'people' | 'contacts'>('vessels');
  const [vesselFormOpen, setVesselFormOpen] = useState(false);
  const [editingVessel, setEditingVessel] = useState<VesselResponse | null>(null);
  const [companyFormOpen, setCompanyFormOpen] = useState(false);
  const [editingCompany, setEditingCompany] = useState<CompanyResponse | null>(null);

  const openCompany = (id: number, tab: 'vessels' | 'people' | 'contacts' = 'vessels') => {
    setCompanyTab(tab);
    setCompanyId(id);
  };

  // A person has no drawer of its own — their details live in their company's People tab.
  const openPerson = (e: RecentEntry) => {
    if (e.companyId != null) openCompany(e.companyId, 'people');
    else navigate('/people');
  };

  return (
    <>
      <Row gutter={16}>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic title="Vessels" value={counts.data?.vessels ?? 0} loading={counts.isLoading} />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic title="Companies" value={counts.data?.companies ?? 0} loading={counts.isLoading} />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card>
            <Statistic title="Contacts" value={counts.data?.contacts ?? 0} loading={counts.isLoading} />
          </Card>
        </Col>
      </Row>

      <Typography.Title level={4} style={{ marginTop: 24 }}>
        Recently opened
      </Typography.Title>
      <Row gutter={16}>
        <Col xs={24} lg={8}>
          <RecentCard
            kind="vessel"
            title="Vessels"
            entries={recentVessels}
            emptyText="No vessels opened yet"
            onOpenPage={() => navigate('/vessels')}
            onOpen={(e) => setVesselId(e.id)}
          />
        </Col>
        <Col xs={24} lg={8}>
          <RecentCard
            kind="company"
            title="Companies"
            entries={recentCompanies}
            emptyText="No companies opened yet"
            onOpenPage={() => navigate('/companies')}
            onOpen={(e) => openCompany(e.id)}
          />
        </Col>
        <Col xs={24} lg={8}>
          <RecentCard
            kind="person"
            title="People"
            entries={recentPeople}
            emptyText="No people opened yet"
            onOpenPage={() => navigate('/people')}
            onOpen={openPerson}
          />
        </Col>
      </Row>

      <VesselDrawer
        vesselId={vesselId}
        onClose={() => setVesselId(undefined)}
        onEdit={(v) => { setEditingVessel(v); setVesselFormOpen(true); }}
      />
      <VesselForm open={vesselFormOpen} editing={editingVessel} onClose={() => setVesselFormOpen(false)} />
      <CompanyDrawer
        companyId={companyId}
        initialTab={companyTab}
        onClose={() => setCompanyId(undefined)}
        onEdit={(c) => { setEditingCompany(c); setCompanyFormOpen(true); }}
      />
      <CompanyForm open={companyFormOpen} editing={editingCompany} onClose={() => setCompanyFormOpen(false)} />
    </>
  );
}

function RecentCard({
  kind,
  title,
  entries,
  emptyText,
  onOpen,
  onOpenPage,
}: {
  kind: RecentKind;
  title: string;
  entries: RecentEntry[];
  emptyText: string;
  onOpen: (entry: RecentEntry) => void;
  onOpenPage: () => void;
}) {
  return (
    <Card
      title={`${title} (${entries.length})`}
      extra={
        <Space size={12}>
          <a onClick={onOpenPage}>open</a>
          {entries.length > 0 && <a onClick={() => clearRecent(kind)}>clear</a>}
        </Space>
      }
    >
      {entries.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={emptyText} />
      ) : (
        <List
          dataSource={entries}
          renderItem={(e) => (
            <List.Item actions={[<RelativeTime key="t" at={e.at} />]}>
              <List.Item.Meta
                title={<Typography.Link onClick={() => onOpen(e)}>{e.title}</Typography.Link>}
                description={e.subtitle ?? '—'}
              />
            </List.Item>
          )}
        />
      )}
    </Card>
  );
}

function RelativeTime({ at }: { at: number }) {
  const mins = Math.max(0, Math.round((Date.now() - at) / 60000));
  const label =
    mins < 1 ? 'just now' : mins < 60 ? `${mins}m ago` : mins < 1440 ? `${Math.round(mins / 60)}h ago` : `${Math.round(mins / 1440)}d ago`;
  return (
    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
      {label}
    </Typography.Text>
  );
}
