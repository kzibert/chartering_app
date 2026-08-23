import { useEffect, useMemo, useState } from 'react';
import { Button, Drawer, List, Popconfirm, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import {
  useContactMutations,
  usePerson,
  usePersonContacts,
  usePersonMutations,
} from '../../api/hooks';
import ContactLine from '../../components/ContactLine';
import EditToolbar, { useEditMode } from '../../components/EditToolbar';
import GreetingName from '../../components/GreetingName';
import LeftCompanyButton from '../../components/LeftCompanyButton';
import ContactForm from '../contacts/ContactForm';
import { recordRecent } from '../../recent/store';
import type { ContactResponse, PersonResponse } from '../../api/types';

interface Props {
  personId?: number;
  onClose: () => void;
  onEdit: (p: PersonResponse) => void;
  /** Supply to make the company name a link back to the company drawer. */
  onOpenCompany?: (companyId: number) => void;
}

/**
 * A person's own emails and phones. Contacts follow the same rules as everywhere else:
 * read-only until Edit is on, which reveals add/edit/delete and the main / not-working
 * toggles.
 */
export default function PersonDrawer({ personId, onClose, onEdit, onOpenCompany }: Props) {
  const { data: p, isLoading } = usePerson(personId);
  const { data: contacts, isLoading: loadingContacts } = usePersonContacts(personId);
  const { remove } = usePersonMutations();
  const { remove: removeContact } = useContactMutations();

  const [editing, setEditing] = useEditMode(personId);
  const [contactFormOpen, setContactFormOpen] = useState(false);
  const [editingContact, setEditingContact] = useState<ContactResponse | null>(null);

  // A contact added here belongs to this person, at their company.
  const contactDefaults = useMemo(
    () => ({ personId, companyId: p?.companyId }),
    [personId, p?.companyId],
  );

  // Feeds the dashboard's "recently opened" trail.
  useEffect(() => {
    if (p) {
      recordRecent({
        kind: 'person',
        id: p.id,
        title: p.fullName,
        subtitle: p.companyName,
        companyId: p.companyId,
      });
    }
  }, [p?.id, p?.fullName, p?.companyName]); // eslint-disable-line react-hooks/exhaustive-deps

  const openContactForm = (ct: ContactResponse | null) => {
    setEditingContact(ct);
    setContactFormOpen(true);
  };

  const rows = contacts?.content ?? [];

  return (
    <Drawer
      open={personId != null}
      width={560}
      title={p?.fullName ?? 'Person'}
      onClose={onClose}
      extra={
        p && (
          <Space>
            <Button onClick={() => onEdit(p)}>Edit</Button>
            <Popconfirm
              title="Delete this person?"
              onConfirm={() => remove.mutate(p.id, { onSuccess: onClose })}
            >
              <Button danger loading={remove.isPending}>Delete</Button>
            </Popconfirm>
          </Space>
        )
      }
    >
      {isLoading || !p ? (
        <Spin />
      ) : (
        <>
          <Space style={{ marginBottom: 12 }} wrap>
            {p.greetingName && <GreetingName title={p.title} name={p.greetingName} type="success" />}
            {p.jobTitle && (
              <Tooltip title="Position at this company. It belongs to the person, so every address and number below carries it.">
                <Tag>{p.jobTitle}</Tag>
              </Tooltip>
            )}
            {p.companyName &&
              (onOpenCompany && p.companyId != null ? (
                <Typography.Link onClick={() => onOpenCompany(p.companyId!)}>
                  {p.companyName}
                </Typography.Link>
              ) : (
                <Tag>{p.companyName}</Tag>
              ))}
            {!p.legacy && <Tag color="green">new</Tag>}
            {p.hasLeft && (
              <Tooltip title={`No longer at ${p.companyName ?? 'this company'}. Every address and number below is out of circulations — left out of collection, and skipped at send time even when already on a list.`}>
                <Tag color="red">left the company</Tag>
              </Tooltip>
            )}
            <LeftCompanyButton p={p} />
          </Space>
          {p.notes && (
            <Typography.Paragraph style={{ marginBottom: 12, whiteSpace: 'pre-wrap' }}>
              <Typography.Text type="secondary">Notes: </Typography.Text>
              {p.notes}
            </Typography.Paragraph>
          )}

          <Typography.Title level={5} style={{ marginTop: 8 }}>
            Contacts ({rows.length})
          </Typography.Title>
          <EditToolbar editing={editing} onToggle={setEditing}>
            <Tooltip title="Add an email or phone for this person">
              <Button size="small" icon={<PlusOutlined />} onClick={() => openContactForm(null)}>
                Add contact
              </Button>
            </Tooltip>
          </EditToolbar>
          <List
            size="small"
            loading={loadingContacts}
            dataSource={rows}
            locale={{ emptyText: 'No contacts' }}
            renderItem={(ct) => (
              <ContactLine
                ct={ct}
                showGreeting={false}
                editing={editing}
                onEdit={openContactForm}
                onDelete={(target) => removeContact.mutate(target.id)}
              />
            )}
          />

          <ContactForm
            open={contactFormOpen}
            editing={editingContact}
            defaults={contactDefaults}
            onClose={() => setContactFormOpen(false)}
          />
        </>
      )}
    </Drawer>
  );
}
